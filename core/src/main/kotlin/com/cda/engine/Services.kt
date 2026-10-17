package com.cda.engine

interface AwsServiceSimulator {
    val serviceName: String
    val operations: Map<String, OperationSpec>
    fun execute(command: SimulationCommand, world: WorldState): WorldState
}
data class OperationSpec(val action: String, val parameters: String, val optional: Set<String> = emptySet()) {
    val keys get()=parameters.split(' ').filter { it.isNotBlank() }.toSet()+optional
}
fun WorldState.resource(id: String,type: String?=null): Resource = resources.find { it.id==id && (type==null || it.type==type) } ?: error("ResourceNotFound: $id ${type.orEmpty()}")
fun WorldState.update(r: Resource)=copy(resources=resources.map { if(it.id==r.id)r else it })
fun WorldState.update(id: String,attrs: Map<String,String>)=update(resource(id).let { it.copy(attrs=it.attrs+attrs) })
fun WorldState.add(r: Resource): WorldState { require(resources.none { it.id==r.id }) { "AlreadyExists: ${r.id}" };require(r.id.matches(Regex("[A-Za-z0-9_.@-]{1,64}"))) { "Invalid resource name" };return copy(resources=resources+r) }
fun WorldState.observe(kind: String,id: String,detail: String)=copy(observations=observations+Observation(tick,kind,id,detail))
fun SimulationCommand.arg(key: String)=args[key]?.takeIf { it.isNotBlank() } ?: error("MissingParameter: $key")
fun SimulationCommand.number(key: String,min: Int=0,max: Int=65535): Int=arg(key).toInt().also { require(it in min..max) { "Invalid $key" } }
fun SimulationCommand.flag(key: String)=arg(key).also { require(it in listOf("true","false")) { "Invalid boolean: $key" } }
fun Resource.csv(key: String)=attrs[key].orEmpty().split(',').filter { it.isNotBlank() }

object SimulatorRegistry {
    val simulators: List<AwsServiceSimulator> by lazy { listOf(NetworkSimulator,StorageSimulator,DatabaseSimulator,TrafficSimulator,MonitoringSimulator,AutomationSimulator,ContainerSimulator,SecuritySimulator) }
    val operations: Map<String,OperationSpec> get()=simulators.flatMap { it.operations.entries }.associate { it.toPair() }
    fun supports(c: SimulationCommand)=simulators.any { c.op in it.operations }
    fun execute(c: SimulationCommand,w: WorldState): WorldState {
        val service=simulators.first { c.op in it.operations };val spec=service.operations.getValue(c.op)
        require(c.args.keys.all { it in spec.keys }) { "Unsupported parameter; supported: ${spec.keys}" }
        spec.parameters.split(' ').filter { it.isNotBlank() }.forEach { c.arg(it) }
        return service.execute(c,w)
    }
    fun action(c: SimulationCommand,w: WorldState?=null)=when {
        c.op in listOf("putFirewallRule","removeFirewallRule") && w?.resources?.find { it.id==c.id }?.type=="nacl" -> "ec2:ReplaceNetworkAclEntry"
        c.op=="putFirewallRule" && c.args["direction"]=="out" -> "ec2:AuthorizeSecurityGroupEgress"
        c.op=="removeFirewallRule" && c.args["direction"]=="out" -> "ec2:RevokeSecurityGroupEgress"
        c.op=="inspectContainers" && w?.resources?.find { it.id==c.id }?.type=="deployment" -> "eks:DescribeDeployment"
        c.op=="createCluster" && c.args["kind"]=="ecs" -> "ecs:CreateCluster"
        c.op=="createGateway" && c.args["kind"]=="nat" -> "ec2:CreateNatGateway"
        else -> operations[c.op]?.action
    }
    fun authorizeRelated(c: SimulationCommand,w: WorldState) {
        if(c.op=="configureBucket") {
            val actions=mapOf("versioning" to "s3:PutBucketVersioning","blockPublicAccess" to "s3:PutBucketPublicAccessBlock","publicRead" to "s3:PutBucketPolicy","lifecycleDays" to "s3:PutLifecycleConfiguration","storageClass" to "s3:PutLifecycleConfiguration","encryptionKey" to "s3:PutEncryptionConfiguration")
            c.args.keys.forEach { key -> actions[key]?.let { action -> require(Iam.allowed(w,w.identity,action,"arn:aws:s3:::${c.id}")) { "AccessDenied: $action" } } }
        }
        if(c.op in listOf("createFunction","registerTask","configureTask","createCluster") && c.args.containsKey("role")) {
            require(Iam.allowed(w,w.identity,"iam:PassRole","arn:aws:iam::${w.account}:role/${c.arg("role")}")) { "AccessDenied: iam:PassRole" }
        }
    }
    fun arn(c: SimulationCommand,w: WorldState,action: String): String = when {
        action.startsWith("s3:") -> "arn:aws:s3:::${c.id}"+(c.args["key"]?.let { "/$it" } ?: "")
        action.startsWith("kms:") -> "arn:aws:kms:${w.region}:${w.account}:key/${c.args["key"] ?: w.resources.find { it.id==c.id && it.type=="ciphertext" }?.attrs?.get("key") ?: c.id}"
        else -> "arn:aws:${action.substringBefore(':')}:${w.region}:${w.account}:${c.id}"
    }
}

object NetworkSimulator: AwsServiceSimulator {
    override val serviceName="VPC"
    override val operations=mapOf(
        "createVpc" to OperationSpec("ec2:CreateVpc","cidr"),
        "createSubnet" to OperationSpec("ec2:CreateSubnet","vpc cidr az"),
        "createRouteTable" to OperationSpec("ec2:CreateRouteTable","vpc"),
        "associateRouteTable" to OperationSpec("ec2:AssociateRouteTable","routeTable"),
        "createGateway" to OperationSpec("ec2:CreateInternetGateway","kind",setOf("vpc","subnet","publicIp")),
        "attachGateway" to OperationSpec("ec2:AttachInternetGateway","vpc"),
        "putRoute" to OperationSpec("ec2:CreateRoute","cidr target"),
        "createFirewall" to OperationSpec("ec2:CreateSecurityGroup","kind vpc"),
        "associateNacl" to OperationSpec("ec2:ReplaceNetworkAclAssociation","nacl"),
        "putFirewallRule" to OperationSpec("ec2:AuthorizeSecurityGroupIngress","direction cidr protocol fromPort toPort number effect"),
        "removeFirewallRule" to OperationSpec("ec2:RevokeSecurityGroupIngress","direction number"),
        "setPublicIp" to OperationSpec("ec2:AssociateAddress","address"),
        "setListener" to OperationSpec("ssm:SendCommand","ports"),
        "probeNetwork" to OperationSpec("sim:ProbeNetwork","destination protocol port")
    )
    override fun execute(command: SimulationCommand,world: WorldState): WorldState {
        val c=command;val w=world
        fun add(type: String,attrs: Map<String,String>)=w.add(Resource(c.id,type,w.region,attrs))
        return when(c.op) {
            "createVpc" -> { val cidr=Cidr.parse(c.arg("cidr"));require(cidr.prefix in 16..28);add("vpc",c.args) }
            "createSubnet" -> {
                val vpc=w.resource(c.arg("vpc"),"vpc");val cidr=Cidr.parse(c.arg("cidr"));require(Cidr.parse(vpc.attrs.getValue("cidr")).contains(cidr) && cidr.prefix in 16..28 && vpc.region==w.region) { "Subnet outside VPC" }
                require(c.arg("az") in listOf("${w.region}a","${w.region}c"))
                require(w.resources.filter { it.type=="subnet" && it.attrs["vpc"]==vpc.id }.none { val other=Cidr.parse(it.attrs.getValue("cidr"));cidr.contains(other) || other.contains(cidr) }) { "Overlapping subnet" };add("subnet",c.args)
            }
            "createRouteTable" -> { val vpc=w.resource(c.arg("vpc"),"vpc");w.add(Resource(c.id,"routeTable",w.region,c.args,routes=listOf(Route(vpc.attrs.getValue("cidr"),"local")))) }
            "associateRouteTable" -> { val s=w.resource(c.id,"subnet");val rt=w.resource(c.arg("routeTable"),"routeTable");require(s.attrs["vpc"]==rt.attrs["vpc"]);w.update(c.id,c.args) }
            "createGateway" -> when(c.arg("kind")) {
                "igw" -> add("igw",mapOf("attached" to "false"))
                "nat" -> { val s=w.resource(c.arg("subnet"),"subnet");Cidr.address(c.arg("publicIp"));add("nat",mapOf("subnet" to s.id,"publicIp" to c.arg("publicIp"),"state" to "available")) }
                else -> error("Unsupported gateway type")
            }
            "attachGateway" -> { w.resource(c.id,"igw");val v=w.resource(c.arg("vpc"),"vpc");require(w.resources.none { it.type=="igw" && it.id!=c.id && it.attrs["vpc"]==v.id && it.attrs["attached"]=="true" });w.update(c.id,c.args+("attached" to "true")) }
            "putRoute" -> {
                val rt=w.resource(c.id,"routeTable");val dest=Cidr.parse(c.arg("cidr"));val target=c.arg("target");val vpc=w.resource(rt.attrs.getValue("vpc"),"vpc")
                require(c.arg("cidr")!=vpc.attrs["cidr"]) { "Cannot replace local route" }
                require(dest.prefix>=0);val g=w.resource(target);require(g.type in listOf("igw","nat"));require(if(g.type=="igw")g.attrs["vpc"]==vpc.id else w.resource(g.attrs.getValue("subnet"),"subnet").attrs["vpc"]==vpc.id)
                w.update(rt.copy(routes=rt.routes.filterNot { it.cidr==c.arg("cidr") }+Route(c.arg("cidr"),target)))
            }
            "createFirewall" -> { w.resource(c.arg("vpc"),"vpc");require(c.arg("kind") in listOf("sg","nacl"));add(c.arg("kind"),mapOf("vpc" to c.arg("vpc"))) }
            "associateNacl" -> { val s=w.resource(c.id,"subnet");val n=w.resource(c.arg("nacl"),"nacl");require(s.attrs["vpc"]==n.attrs["vpc"]);w.update(c.id,c.args) }
            "putFirewallRule" -> {
                val r=w.resource(c.id);require(r.type in listOf("sg","nacl"));require(c.arg("direction") in listOf("in","out"));Cidr.parse(c.arg("cidr"));require(c.arg("protocol") in listOf("tcp","udp","all"));val from=c.number("fromPort");val to=c.number("toPort");require(from<=to);val number=c.number("number",1,32766);require(c.arg("effect") in if(r.type=="sg")listOf("Allow") else listOf("Allow","Deny"))
                val rule=FirewallRule(c.arg("direction"),c.arg("cidr"),c.arg("protocol"),from,to,number,c.arg("effect"));w.update(r.copy(rules=r.rules.filterNot { it.direction==rule.direction && it.number==number }+rule))
            }
            "removeFirewallRule" -> { val r=w.resource(c.id);require(r.type in listOf("sg","nacl"));val n=c.number("number",1,32766);w.update(r.copy(rules=r.rules.filterNot { it.direction==c.arg("direction") && it.number==n })) }
            "setPublicIp" -> { val r=w.resource(c.id);require(r.type in listOf("instance","alb","rds"));if(c.arg("address")!="none")Cidr.address(c.arg("address"));w.update(c.id,mapOf("publicIp" to if(c.arg("address")=="none")"" else c.arg("address"))) }
            "setListener" -> { w.resource(c.id,"instance");val ports=c.arg("ports").split(',').map { it.toInt() };require(ports.all { it in 1..65535 });w.update(c.id,mapOf("listenPorts" to ports.joinToString(","))) }
            "probeNetwork" -> { val result=NetworkEvaluator().evaluate(c.id,c.arg("destination"),c.arg("protocol"),c.number("port",1),w);w.observe("network",c.id,"${result.reachable}: ${result.reason}; ${result.path.joinToString(" → ")}") }
            else -> error("Unsupported VPC operation")
        }
    }
}
