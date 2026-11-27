package io.github.chuck852.linksonda.network

import io.github.chuck852.linksonda.domain.IpFamily
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun interface HostResolver {
    suspend fun resolve(host: String, family: IpFamily?): List<InetAddress>
}

internal class SystemHostResolver : HostResolver {
    override suspend fun resolve(host: String, family: IpFamily?): List<InetAddress> =
        withContext(Dispatchers.IO) {
            val addresses = InetAddress.getAllByName(host)
            val matching = addresses.filter { address ->
                when (family) {
                    null -> true
                    IpFamily.IPV4 -> address is Inet4Address
                    IpFamily.IPV6 -> address is Inet6Address
                }
            }
            if (matching.isEmpty()) {
                throw UnknownHostException("No ${family?.name ?: "usable"} address for $host")
            }
            matching
        }
}
