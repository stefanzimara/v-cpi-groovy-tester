// This is Groovy Flowstep Version 2.x, running with Groovy runtime 4.

import com.sap.it.script.v2.api.Message
import groovy.json.JsonSlurper
import org.apache.commons.lang3.StringEscapeUtils

/**
 * Minimal example script for the CPI Groovy Tester (Script Version 2.x).
 *
 * Same behavior as scripts/OrderToXml.groovy - only the Message import
 * differs, since that is what the tester uses to tell the two script
 * generations apart. Everything else about writing a Script Version 2.x
 * script is identical.
 */
Message processData(Message message) {
    def props = message.getProperties()
    def body = message.getBody(String) ?: ''
    def order = body.trim() ? new JsonSlurper().parseText(body) : [:]

    def orderId = order.orderId ?: ''
    def customerName = order.customer?.name ?: ''
    def items = order.items ?: []
    def total = items.sum { (it.price ?: 0) * (it.quantity ?: 0) } ?: 0

    def source = message.getHeader('SourceSystem') ?: 'unknown'
    def currency = props.get('currency') ?: 'EUR'

    def xml = """<Order>
  <OrderId>${esc(orderId)}</OrderId>
  <Customer>${esc(customerName)}</Customer>
  <ItemCount>${items.size()}</ItemCount>
  <Total currency="${esc(currency)}">${total}</Total>
  <Source>${esc(source)}</Source>
</Order>"""

    message.setBody(xml)
    message.setProperty('orderId', orderId)

    def log = messageLogFactory.getMessageLog(message)
    log?.addAttachmentAsString('Parsed order', order.toString(), 'text/plain')

    return message
}

String esc(value) {
    StringEscapeUtils.escapeXml11(value?.toString() ?: '')
}
