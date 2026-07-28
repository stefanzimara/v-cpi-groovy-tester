import com.sap.gateway.ip.core.customdev.util.Message
import groovy.json.JsonSlurper
import org.apache.commons.lang3.StringEscapeUtils

/**
 * Minimal example script for the CPI Groovy Tester (Script Version 1.x).
 *
 * Reads a small JSON order and writes an XML summary. Deliberately not tied
 * to any particular business domain - it exists to show the pieces most CPI
 * scripts touch: message body, headers, properties, and the MessageLog.
 *
 * Try it with: testdata/order.json / testdata/config.json
 */
Message processData(Message message) {
    def props = message.getProperties()
    def body = message.getBody(String) ?: ''
    def order = body.trim() ? new JsonSlurper().parseText(body) : [:]

    def orderId = order.orderId ?: ''
    def customerName = order.customer?.name ?: ''
    def items = order.items ?: []
    def total = items.sum { (it.price ?: 0) * (it.quantity ?: 0) } ?: 0

    // Headers and properties behave exactly like in a real CPI flow step.
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

    // messageLog is null unless "messageLog active" is checked in the tester -
    // exactly like a CPI trace that isn't running.
    def log = messageLogFactory.getMessageLog(message)
    log?.addAttachmentAsString('Parsed order', order.toString(), 'text/plain')

    return message
}

String esc(value) {
    StringEscapeUtils.escapeXml11(value?.toString() ?: '')
}
