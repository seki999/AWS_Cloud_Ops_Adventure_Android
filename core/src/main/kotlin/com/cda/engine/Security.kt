package com.cda.engine

object SecuritySimulator: AwsServiceSimulator {
    override val serviceName="Security"
    override val operations=mapOf(
        "createOrganization" to OperationSpec("organizations:CreateOrganization",""),
        "putScp" to OperationSpec("organizations:AttachPolicy","organization account denyActions resources"),
        "createKey" to OperationSpec("kms:CreateKey","principals"),
        "configureKey" to OperationSpec("kms:PutKeyPolicy","principals enabled"),
        "encryptData" to OperationSpec("kms:Encrypt","key content"),
        "decryptData" to OperationSpec("kms:Decrypt",""),
        "replicateResource" to OperationSpec("backup:CopyIntoBackupVault","source region")
    )
    override fun execute(command: SimulationCommand,world: WorldState): WorldState {
        val c=command;val w=world
        fun principals() { c.arg("principals").split(',').forEach { val r=w.resource(it);require(r.type in listOf("user","role")) } }
        return when(c.op) {
            "createOrganization" -> w.add(Resource(c.id,"organization",w.region,mapOf("memberAccount" to w.account)))
            "putScp" -> {
                w.resource(c.arg("organization"),"organization");require(c.arg("account")==w.account) { "Only this simulated member account is supported" }
                val actions=c.arg("denyActions").split(',');require(actions.all { it.contains(':') && it.none { ch -> ch.isWhitespace() } });val p=Policy("Deny",actions,c.arg("resources").split(','));val r=Resource(c.id,"scp",w.region,c.args+mapOf("enabled" to "true"),listOf(p))
                if(w.resources.any { it.id==c.id }) { w.resource(c.id,"scp");w.update(r) } else w.add(r)
            }
            "createKey" -> { principals();w.add(Resource(c.id,"kmsKey",w.region,c.args+("enabled" to "true"))) }
            "configureKey" -> { w.resource(c.id,"kmsKey");principals();c.flag("enabled");w.update(c.id,c.args) }
            "encryptData" -> { require(ServiceRules.keyAllowed(w,c.arg("key"),w.identity,"kms:Encrypt")) { "Key policy or IAM denied" };w.add(Resource(c.id,"ciphertext",w.region,mapOf("key" to c.arg("key"),"payload" to c.arg("content")))) }
            "decryptData" -> { val r=w.resource(c.id,"ciphertext");require(ServiceRules.keyAllowed(w,r.attrs.getValue("key"),w.identity,"kms:Decrypt")) { "Key policy or IAM denied" };w.observe("decrypt",c.id,r.attrs.getValue("payload")) }
            "replicateResource" -> {
                val source=w.resource(c.arg("source"));val region=c.arg("region");require(region in listOf("ap-northeast-1","us-east-1","eu-west-1") && region!=source.region) { "Choose a different supported region" }
                require(source.type in listOf("bucket","dbBackup","snapshot")) { "Cross-region copies support S3 and backups, not live infrastructure" }
                require(Iam.allowed(w,w.identity,if(source.type=="bucket")"s3:GetObject" else "backup:CopyFromBackupVault",if(source.type=="bucket")"arn:aws:s3:::${source.id}/*" else "*")) { "Source copy permission denied" }
                require(source.type!="bucket" || source.objects.all { it.encryptionKey.isBlank() }) { "KMS cross-region keys are not supported; copy denied" }
                w.add(source.copy(id=c.id,region=region,attrs=source.attrs+("source" to source.id)))
            }
            else -> error("Unsupported security operation")
        }
    }
}
