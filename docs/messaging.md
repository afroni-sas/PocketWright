# Messaging

PocketWright can become the phone's SMS app, and can then stream incoming messages over the API. Both are off by default.

> [!CAUTION]
> Once it is the messaging app, PocketWright holds your SMS and contacts. Read [SECURITY.md](../SECURITY.md) first, especially
> on a phone that receives bank or login codes.

## Enabling it

1. In **Paramètres → Messagerie** or **menu → Messages**, choose « Définir comme messagerie ». This opens Android's official request.
2. To use the API, turn on **Donner à l'API l'accès aux messages**. The API cannot flip this switch itself.

To go back, use Android's settings: default apps, then SMS app.

## What the app handles

Receiving, saving to Android's SMS storage (so the next messaging app can still read them), notifications, conversations,
sending, quick replies to calls, and `sms:` links. Received MMS are downloaded and displayed with their images.

It cannot send MMS. RCS is closed to third-party apps.

## Stream and commands

One WebSocket connection receives incoming messages and accepts every command:

```text
ws://127.0.0.1:8765/messages/stream?key=$KEY
```

To only listen, the same stream exists as Server-Sent Events:

```bash
curl -N http://127.0.0.1:8765/messages/stream -H "Authorization: Bearer $KEY"
```

The stream starts with `{"type":"ready","lastSeq":12}`, then sends one object per message:

```json
{"type":"message","seq":13,"kind":"sms","id":345,"threadId":9,"from":"+33639980001","box":"incoming","read":false,"body":"Hello","date":1762600000000,"dateSent":1762600000000,"subId":1}
```

## Commands

On the WebSocket, in the same format as `/ws`:

```json
{"id": 1, "action": "send", "params": {"to": "+33639980001", "text": "Hello"}}
```

Each command receives `{"type":"result","id":1,"ok":true,"result":…}`, or `"ok":false` with `error` and `status`.

| | Action | Parameters | Result |
| --- | --- | --- | --- |
| Create | `send` | `to` (a number or a list), `text` (up to 1,600 characters), optional `subId` | `messages`: the SMS created, with `box` set to `sent`, `failed`, or `sending` if the network has not answered after 60 s |
| Read | `conversations` | `limit`, `offset` | The list of conversations |
| | `messages` | `threadId`, `limit`, `before` (date in ms) | The messages of the thread, oldest first |
| | `message` | `id`, `kind` | One message |
| | `attachment` | `partId` | `contentType` and `base64` |
| Update | `update` | `id`, `kind`, then `read` and/or `body` (SMS only); or `threadId` and `read` | The updated message |
| Delete | `delete` | `id`, `kind` | `deleted` |
| | `deleteConversation` | `threadId` | `deleted`: the number of messages removed |

`kind` is `sms` by default, or `mms`. Deletion is permanent, and `send` goes through your phone plan.

## Resuming without loss

`seq` increases with each message. Keep the last one received and reconnect with `?since=<seq>`: anything that arrived in the
meantime is replayed in order, up to the last 5,000 messages. Over SSE, the `Last-Event-ID` header does the same. Without
`since`, only new messages arrive.

## MMS

`kind` is `mms`, with `downloaded` and `attachments` (`contentType`, `name`, `url`). `downloaded:false` signals an MMS that
could not be retrieved. If retrieval succeeds later, it arrives as a new message.

## Routes

| Route | Purpose |
| --- | --- |
| `GET /messages/status` | `defaultApp`, `streaming`, `lastSeq` |
| `GET /messages/stream` | The stream, over SSE, or over WebSocket with commands |
| `GET /messages/parts/{id}` | One attachment, as binary |

Responses: `401` without the key, `403` if access is off, `409` if another app is the messaging app. The connection closes if
the switch is turned off, if the key changes, or if another app becomes the messaging app.
