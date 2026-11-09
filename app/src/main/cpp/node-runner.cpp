// Lance Node.js (nodejs-mobile) dans le processus de l'app, et envoie sa sortie dans logcat.
#include <jni.h>
#include <android/log.h>
#include <pthread.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "node.h"

namespace {

constexpr const char* kTag = "PocketWright-node";

struct Pipe {
    int fd;
    int priority;
};

void* pump(void* arg) {
    auto* p = static_cast<Pipe*>(arg);
    char buffer[2048];
    std::string line;
    ssize_t n;
    while ((n = read(p->fd, buffer, sizeof buffer)) > 0) {
        line.append(buffer, static_cast<size_t>(n));
        size_t end;
        while ((end = line.find('\n')) != std::string::npos) {
            __android_log_write(p->priority, kTag, line.substr(0, end).c_str());
            line.erase(0, end + 1);
        }
    }
    return nullptr;
}

void redirect(int target, int priority) {
    int fds[2];
    if (pipe(fds) != 0) return;
    dup2(fds[1], target);
    close(fds[1]);
    pthread_t thread;
    if (pthread_create(&thread, nullptr, pump, new Pipe{fds[0], priority}) == 0) pthread_detach(thread);
}

}  // namespace

extern "C" JNIEXPORT jint JNICALL
Java_com_afroni_pocketwright_engine_NodeRuntime_start(JNIEnv* env, jobject /* runtime */, jobjectArray arguments) {
    setvbuf(stdout, nullptr, _IOLBF, 0);
    setvbuf(stderr, nullptr, _IONBF, 0);
    redirect(STDOUT_FILENO, ANDROID_LOG_INFO);
    redirect(STDERR_FILENO, ANDROID_LOG_WARN);

    // node lit ses arguments dans un seul bloc de mémoire contigu (pour process.title).
    const jsize argc = env->GetArrayLength(arguments);
    std::vector<std::string> args;
    size_t total = 0;
    for (jsize i = 0; i < argc; ++i) {
        auto s = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char* chars = env->GetStringUTFChars(s, nullptr);
        args.emplace_back(chars);
        env->ReleaseStringUTFChars(s, chars);
        env->DeleteLocalRef(s);
        total += args.back().size() + 1;
    }
    char* block = static_cast<char*>(calloc(total, 1));
    std::vector<char*> argv;
    char* cursor = block;
    for (const auto& arg : args) {
        memcpy(cursor, arg.c_str(), arg.size());
        argv.push_back(cursor);
        cursor += arg.size() + 1;
    }
    argv.push_back(nullptr);
    return node::Start(argc, argv.data());
}
