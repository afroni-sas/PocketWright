package com.afroni.pocketwright.session

/** Une erreur renvoyée telle quelle au client de l'API, avec son code HTTP. */
class ApiException(val status: Int, message: String) : Exception(message)
