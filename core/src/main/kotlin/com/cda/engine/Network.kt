package com.cda.engine

/** Strict IPv4 math; malformed/non-canonical CIDRs fail closed. */
data class Cidr(val network: Long, val prefix: Int) {
    private val mask: Long get() = if(prefix==0)0 else (0xffffffffL shl (32-prefix)) and 0xffffffffL
    fun contains(ip: String) = (address(ip) and mask)==network
    fun contains(other: Cidr) = prefix<=other.prefix && (other.network and mask)==network
    companion object {
        fun address(ip: String): Long {
            val parts=ip.split('.'); require(parts.size==4) { "Invalid IPv4" }
            return parts.fold(0L) { n,p -> require(p.matches(Regex("0|[1-9][0-9]{0,2}")) && p.toInt() in 0..255) { "Invalid IPv4" }; (n shl 8)+p.toInt() }
        }
        fun parse(value: String): Cidr {
            val p=value.split('/');require(p.size==2) { "CIDR prefix required" }
            val prefix=p[1].toInt();require(prefix in 0..32)
            val ip=address(p[0]);val mask=if(prefix==0)0L else (0xffffffffL shl (32-prefix)) and 0xffffffffL
            require((ip and mask)==ip) { "CIDR must use network address" };return Cidr(ip,prefix)
        }
    }
}
data class ConnectivityResult(val reachable: Boolean, val reason: String, val path: List<String> = emptyList())
class NetworkEvaluator {
    fun longestPrefix(routes: List<Route>, ip: String): Route? = routes.filter { Cidr.parse(it.cidr).contains(ip) }.maxByOrNull { Cidr.parse(it.cidr).prefix }
    fun evaluate(source: String,destination: String,protocol: String,port: Int,world: WorldState): ConnectivityResult = try {
        require(protocol in listOf("tcp","udp") && port in 1..65535) { "Unsupported protocol or port" }
        val path=mutableListOf<String>()
        fun resource(id: String)=world.resources.find { it.id==id } ?: error("ResourceNotFound: $id")
        fun endpoint(id: String): Resource?=if(id=="internet")null else resource(id)
        val src=endpoint(source);val dst=endpoint(destination);require(src!=null || dst!=null) { "No VPC endpoint" }
        fun subnet(r: Resource)=resource(r.attrs["subnet"] ?: error("Missing subnet: ${r.id}"))
        fun ip(r: Resource?)=r?.attrs?.get("privateIp") ?: if(r==null)"203.0.113.10" else error("Missing private IP: ${r.id}")
        fun health(r: Resource?) { if(r!=null) { require(r.attrs["state"] in listOf("running","available","active")) { "Endpoint stopped: ${r.id}" }; require(r.attrs["system"]!="failed") { "System check failed" };val s=subnet(r);val vpc=resource(s.attrs.getValue("vpc"));require(Cidr.parse(s.attrs.getValue("cidr")).contains(ip(r)) && Cidr.parse(vpc.attrs.getValue("cidr")).contains(Cidr.parse(s.attrs.getValue("cidr")))) { "Invalid subnet or address ownership" }; require(s.region==r.region) { "Region mismatch" } } }
        health(src);health(dst)
        if(dst!=null) { require(dst.attrs["app"]!="stopped") { "Application stopped" };require(port in dst.attrs["listenPorts"].orEmpty().split(',').mapNotNull { it.toIntOrNull() }) { "Port not listening" } }
        fun rules(r: Resource,direction: String,peer: String,p: Int,sg: Boolean): Boolean {
            val s=subnet(r);val firewall=if(sg)resource(r.attrs["sg"] ?: error("Missing security group")) else s.attrs["nacl"]?.let { resource(it) } ?: return true
            require(firewall.type==(if(sg)"sg" else "nacl") && firewall.attrs["vpc"]==s.attrs["vpc"]) { "Firewall VPC mismatch" }
            val matches=firewall.rules.filter { it.direction==direction && it.protocol in listOf(protocol,"all") && p in it.fromPort..it.toPort && Cidr.parse(it.cidr).contains(peer) }
            return if(sg)matches.any { it.effect=="Allow" } else matches.minByOrNull { it.number }?.effect=="Allow"
        }
        fun nacl(r: Resource?,direction: String,peer: String,p: Int) { if(r!=null)require(rules(r,direction,peer,p,false)) { "NACL $direction denied ${r.id}:$p" } }
        fun igw(s: Resource,peer: String) {
            val rt=resource(s.attrs["routeTable"] ?: error("Missing route table"));require(rt.attrs["vpc"]==s.attrs["vpc"]) { "Route table VPC mismatch" }
            val route=longestPrefix(rt.routes,peer) ?: error("No matching route")
            val gateway=resource(route.target);require(gateway.type=="igw" && gateway.attrs["vpc"]==s.attrs["vpc"] && gateway.attrs["attached"]=="true") { "Internet gateway missing/detached" };path+=gateway.id
        }
        fun route(from: Resource?,to: Resource?) {
            if(from==null)return
            val s=subnet(from);val peer=ip(to)
            val rt=resource(s.attrs["routeTable"] ?: error("Missing route table"));require(rt.attrs["vpc"]==s.attrs["vpc"]) { "Route table VPC mismatch" }
            val selected=longestPrefix(rt.routes,peer) ?: error("No matching route to $peer")
            path+=s.id;path+=rt.id
            if(to!=null) { require(from.region==to.region && s.attrs["vpc"]==subnet(to).attrs["vpc"] && selected.target=="local") { "No supported private return route" } }
            else if(selected.target!="local") {
                val g=resource(selected.target)
                if(g.type=="igw") { require(from.attrs["publicIp"].orEmpty().isNotBlank()) { "No public IP" };Cidr.address(from.attrs.getValue("publicIp"));igw(s,peer) }
                else { require(g.type=="nat" && g.attrs["state"]=="available" && !g.attrs["publicIp"].isNullOrBlank()) { "NAT unavailable" };val ns=resource(g.attrs.getValue("subnet"));require(ns.attrs["vpc"]==s.attrs["vpc"]) { "NAT in different VPC" };igw(ns,peer);nacl(g,"out",peer,port);nacl(g,"in",peer,49152);path+=g.id }
            } else error("Local route cannot reach internet")
        }
        if(src==null) { require(!dst!!.attrs["publicIp"].isNullOrBlank()) { "No public IP" };Cidr.address(dst.attrs.getValue("publicIp"));igw(subnet(dst),ip(null)) }
        route(src,dst)
        if(src!=null)require(rules(src,"out",ip(dst),port,true)) { "Security group egress denied" }
        if(dst!=null)require(rules(dst,"in",ip(src),port,true)) { "Security group ingress denied" }
        nacl(src,"out",ip(dst),port);nacl(dst,"in",ip(src),port)
        // SG response traffic is stateful. NACL response traffic must explicitly allow ephemeral ports.
        nacl(dst,"out",ip(src),49152);nacl(src,"in",ip(dst),49152)
        if(dst!=null)route(dst,src)
        ConnectivityResult(true,"Reachable (request and return path)",path.distinct())
    } catch(e: Exception) { ConnectivityResult(false,e.message ?: "Network denied") }
}
