package com.sap.it.script.v2.api;

import de.cpitester.MessageSupport;

/**
 * Lokaler Nachbau der Message-Klasse aus Groovy Script Version 2.x
 * (Groovy-4-Laufzeit).
 *
 * Der Methodenumfang ist bewusst identisch zur Version-1-Klasse: getBody(Class),
 * setBody, get/setProperty(ies), get/setHeader(s), Attachments. Ruft ein Script
 * eine Methode auf, die es hier nicht gibt, meldet Groovy das mit einer
 * MissingMethodException - dann fehlt sie im Nachbau und kann ergaenzt werden.
 */
public class Message extends MessageSupport {
}
