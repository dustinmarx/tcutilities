#!/usr/bin/env groovy

def cli = new CliBuilder(
   usage: 'checkTCServerHCProperties -f <pathToTcConfigXmlFile> [-v] [-h]',
   header: '\nAvailable options (use -h for help):\n',
   footer: '\nParses referenced tc-config.xml file and analyzes its health check parameters..\n')
import org.apache.commons.cli.Option
cli.with
{
   h(longOpt: 'help', 'Usage Information', required: false)
   f(longOpt: 'file', 'Path to tc-config.xml File', args: 1, required: true)
   v(longOpt: 'verbose', 'Specifies verbose output', args: 0, required: false)
}
def opt = cli.parse(args)

if (!opt) return
if (opt.h) cli.usage()

String tcConfigFileName = opt.f
boolean verbose = opt.v

println "Checking ${tcConfigFileName}'s properties..."
def tcConfigXml = new XmlSlurper().parse(tcConfigFileName)
TreeMap<String, String> properties = new TreeSet<>()
tcConfigXml."tc-properties".property.each
{ tcProperty ->
   String tcPropertyName = tcProperty.@name
   String tcPropertyValue = tcProperty.@value
   properties.put(tcPropertyName, tcPropertyValue)
}
if (verbose)
{
   properties.each
   { propertyName, propertyValue ->
      println "${propertyName}: ${propertyValue}"
   }
}

boolean isL2L1PingEnabled = extractBoolean(properties, "l2.healthcheck.l1.ping.enabled")
boolean isL2L2PingEnabled = extractBoolean(properties, "l2.healthcheck.l2.ping.enabled")
boolean isL1L2PingEnabled = extractBoolean(properties, "l1.healthcheck.l2.ping.enabled")
boolean isPingEnabled = isL2L1PingEnabled && isL2L2PingEnabled && isL1L2PingEnabled
println "Health Check Ping ${isPingEnabled ? 'IS' : 'is NOT'} enabled."
if (!isPingEnabled)
{
   System.exit(-1)
}

Long pingIdleTimeL2L1 = extractLong(properties, "l2.healthcheck.l1.ping.idletime")
Long pingIdleTimeL2L2 = extractLong(properties, "l2.healthcheck.l2.ping.idletime")
Long pingIdleTimeL1L2 = extractLong(properties, "l1.healthcheck.l2.ping.idletime")

Long pingIntervalL2L1 = extractLong(properties, "l2.healthcheck.l1.ping.interval")
Long pingIntervalL2L2 = extractLong(properties, "l2.healthcheck.l2.ping.interval")
Long pingIntervalL1L2 = extractLong(properties, "l1.healthcheck.l2.ping.interval")

Long pingProbesL2L1 = extractLong(properties, "l2.healthcheck.l1.ping.probes")
Long pingProbesL2L2 = extractLong(properties, "l2.healthcheck.l2.ping.probes")
Long pingProbesL1L2 = extractLong(properties, "l1.healthcheck.l2.ping.probes")

boolean socketConnectL2L1 = extractBoolean(properties, "l2.healthcheck.l1.socketConnect")
boolean socketConnectL2L2 = extractBoolean(properties, "l2.healthcheck.l2.socketConnect")
boolean socketConnectL1L2 = extractBoolean(properties, "l1.healthcheck.l2.socketConnect")

if (!socketConnectL2L1 || !socketConnectL2L2 || !socketConnectL1L2)
{
   println "Socket connect is disabled."
   System.exit(-2)
}

Long socketConnectTimeoutL2L1 = extractLong(properties, "l2.healthcheck.l1.socketConnectTimeout")
Long socketConnectTimeoutL2L2 = extractLong(properties, "l2.healthcheck.l2.socketConnectTimeout")
Long socketConnectTimeoutL1L2 = extractLong(properties, "l1.healthcheck.l2.socketConnectTimeout")

Long socketConnectCountL2L1 = extractLong(properties, "l2.healthcheck.l1.socketConnectCount")
Long socketConnectCountL2L2 = extractLong(properties, "l2.healthcheck.l2.socketConnectCount")
Long socketConnectCountL1L2 = extractLong(properties, "l1.healthcheck.l2.socketConnectCount")

Long maximumL2L1 = calculateMaximumTime(pingIdleTimeL2L1, pingIntervalL2L1, pingProbesL2L1, socketConnectCountL2L1, socketConnectTimeoutL2L1)
Long maximumL2L2 = calculateMaximumTime(pingIdleTimeL2L2, pingIntervalL2L2, pingProbesL2L2, socketConnectCountL2L2, socketConnectTimeoutL2L2)
Long maximumL1L2 = calculateMaximumTime(pingIdleTimeL1L2, pingIntervalL1L2, pingProbesL1L2, socketConnectCountL1L2, socketConnectTimeoutL1L2)

if (verbose)
{
   println "L2-L1 Maximum Time: ${maximumL2L1}"
   println "L2-L2 Maximum Time: ${maximumL2L2}"
   println "L1-L2 Maximum Time: ${maximumL1L2}"
}

long electionTime = 5000
long clientReconnectWindow = 120000

long maximumL2L2Election = maximumL2L2 + electionTime
long maximumL2L2ElectionReconnect = maximumL2L2Election + clientReconnectWindow

if (verbose)
{
   println "L2-L2 Maximum Time + ElectionTime: ${maximumL2L2Election}"
   println "L2-L2 Maximum Time + ElectionTime + Client Reconnect Window: ${maximumL2L2ElectionReconnect}"   
}

if (maximumL1L2 < maximumL2L2Election)
{
   print "WARNING: Will lead to 'High Availability Not Configured Properly: L1L2HealthCheck should be more than L2-L2HealthCheck + ElectionTime' "
   println "because ${maximumL1L2} < ${maximumL2L2Election}."
}
else if (maximumL1L2 > maximumL2L2ElectionReconnect)
{
   print "WARNING: Will lead to 'High Availability Not Configured Properly: L1L2HealthCheck should be less than L2-L2HealthCheck + ElectionTime + ClientReconnectWindow' "
   println "because ${maximumL1L2} > ${maximumL2L2ElectionReconnect}."
}

/**
 * Extract a Boolean value for the provided property name from the provided
 * properties.
 *
 * @return Boolean value associated with the provided property name.
 */
boolean extractBoolean(TreeMap<String, String> properties, String propertyName)
{
   return  properties != null && properties.containsKey(propertyName)
         ? Boolean.valueOf(properties.get(propertyName))
         : false
}

/**
 * Extract a Long value for the provided property name from the provided
 * properties.
 *
 * @return Long value associated with the provided property name.
 */
Long extractLong(TreeMap<String, String> properties, String propertyName)
{
   return  properties != null && properties.containsKey(propertyName)
         ? Long.valueOf(properties.get(propertyName))
         : 0
}

/**
 * Provides the maximum time as calculated using the following formula:
 *
 * Maximum Time =
 *      (ping.idletime) + socketConnectCount *
 *      [(ping.interval * ping.probes) + (socketConnectTimeout * ping.interval)]
 */
Long calculateMaximumTime(Long pingIdleTime, Long pingInterval, Long pingProbes,
   Long socketConnectCount, Long socketConnectTimeout)
{
   return pingIdleTime + socketConnectCount * pingInterval * (pingProbes + socketConnectTimeout)
}
