package com.cda.cloudops

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.*
import com.cda.engine.*
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint class MainActivity: ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); setContent { Game() } }
}
@Composable fun Game(vm: GameViewModel = viewModel()) {
    val w by vm.world.collectAsState(); val lang by vm.language.collectAsState(); val ready by vm.ready.collectAsState(); val output by vm.output.collectAsState()
    val nav=rememberNavController(); var scale by remember { mutableFloatStateOf(1f) }
    fun t(zh: String,ja: String,en: String)=when(lang) { "zh" -> zh; "ja" -> ja; else -> en }
    val colors=if(isSystemInDarkTheme()) darkColorScheme(primary=Color(0xFF6CE9D2),background=Color(0xFF081321),surface=Color(0xFF122338)) else lightColorScheme(primary=Color(0xFF006D66))
    MaterialTheme(colorScheme=colors) {
        Scaffold { padding ->
            Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal=16.dp)) {
                Text("CDA / CLOUD OPS",color=MaterialTheme.colorScheme.primary,fontSize=13.sp,modifier=Modifier.padding(top=16.dp))
                Text("AWS Cloud Ops Adventure",style=MaterialTheme.typography.headlineSmall)
                Text("${w.region} · ${w.identity} · ${w.account}",style=MaterialTheme.typography.labelSmall)
                Text(t("本游戏为 AWS 学习模拟器，并非 AWS 官方控制台，不会创建真实云资源，也不会产生真实 AWS 费用。","AWS 学習シミュレーターです。公式コンソールではなく、実リソースも実際の料金も発生しません。","Educational simulator, not the official AWS console. No real resources or AWS charges."),style=MaterialTheme.typography.labelSmall,modifier=Modifier.padding(vertical=8.dp))
                if(!ready) { Text(output.ifEmpty { t("载入存档…","読込中…","Loading save…") }); return@Column }
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    listOf("home" to t("指挥中心","司令部","Home"),"map" to t("章节","章","Missions"),"console" to t("控制台","コンソール","Console"),"terminal" to "CLI","topology" to t("拓扑","構成図","Topology"),"learning" to t("知识库","学習","Learn"),"settings" to t("设置","設定","Settings")).forEach { (route,label) -> TextButton(onClick={ nav.navigate(route) { launchSingleTop=true } }) { Text(label) } }
                }
                NavHost(nav,startDestination="home",modifier=Modifier.weight(1f)) {
                    composable("home") {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            Spacer(Modifier.height(20.dp)); Text(t("2035 · 云城等待救援","2035 · 都市を救え","2035 · A city awaits rescue"),style=MaterialTheme.typography.headlineMedium)
                            Text(t("Commander Nova：Shadow Fault 切断了城市基础设施。检查资源，建立假设，执行修复。","Nova：Shadow Fault が都市を攻撃。リソースを調べ、仮説を立て、復旧せよ。","Commander Nova: Shadow Fault has attacked. Inspect resources, form a hypothesis, restore the city."),modifier=Modifier.padding(vertical=16.dp))
                            Text("XP ${w.xp}   ◈ ${w.coins}   ${w.completed.size}/${vm.missions.size}")
                            Text(listOf("Cloud Trainee","Junior Cloud Engineer","Cloud Administrator","Network Engineer","Database Operator","Reliability Engineer","Monitoring Specialist","Automation Engineer","Container Engineer","Cloud Commander")[(w.completed.size/6).coerceAtMost(9)])
                            Button(onClick={ if(w.missionId.isEmpty()) vm.start("aws-001"); nav.navigate("console") }) { Text(t("开始 / 继续游戏","開始 / 続ける","Start / Continue")) }
                            OutlinedButton(onClick={nav.navigate("map")}) { Text(t("章节地图","チャプターマップ","Chapter map")) }
                            OutlinedButton(onClick={vm.start("lab");nav.navigate("console")}) { Text(t("自由实验室","自由ラボ","Sandbox lab")) }
                            Text(t("成就","実績","Achievements"),style=MaterialTheme.typography.titleLarge,modifier=Modifier.padding(top=20.dp))
                            listOf("First Instance","IAM Guardian","Network Rescuer","Storage Defender","Database Survivor","Scaling Master","Monitoring Expert","Automation Hero","Container Commander","Cloud Architect").forEach { Text("${if(w.achievementTimes.containsKey(it)) "★" else "○"} $it ${w.achievementTimes[it]?.let { tick -> "· tick $tick" }.orEmpty()}") }
                        }
                    }
                    composable("map") {
                        LazyColumn {
                            item { MapCanvas(w,lang) }
                            items(vm.missions) { m -> val unlocked=m.order==1 || w.completed.containsKey("aws-%03d".format(m.order-1)); OutlinedButton(enabled=unlocked,onClick={vm.start(m.id);nav.navigate("console")},modifier=Modifier.fillMaxWidth()) { Text("${m.id} · ${m.title.get(lang)}  ${if(unlocked)"★".repeat(w.completed[m.id]?:0) else "🔒"}") } }
                        }
                    }
                    composable("console") { Console(vm,w,lang,output) }
                    composable("terminal") { Terminal(vm,lang,output) }
                    composable("topology") { Column { Text(t("拖动节点 · 图形连接不等于网络可达","ノードを移動 · 接続は到達性を保証しません","Drag nodes · visual links do not establish reachability")); Topology(vm,w); w.resources.filter { it.type=="instance" }.forEach { Text("${it.id} → ${it.attrs["subnet"]} → vpc-main") } } }
                    composable("learning") { LazyColumn { items(vm.missions) { m -> Text(m.title.get(lang),style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(top=16.dp)); Text(m.lesson.get(lang),fontSize=(16*scale).sp) } } }
                    composable("settings") { Column { Text(t("语言","言語","Language")); Row { listOf("zh" to "中文","ja" to "日本語","en" to "English").forEach { (l,label) -> TextButton(onClick={vm.language(l)}) { Text(label) } } }; Text(t("教学字体大小","学習の文字サイズ","Learning font size")); Slider(value=scale,onValueChange={scale=it},valueRange=0.8f..1.5f); Text(t("完全离线 · 无 AWS 凭证 · 价格仅用于教学","完全オフライン · 認証情報不要 · 教育用価格","Offline · No AWS credentials · Educational prices")) } }
                }
            }
        }
    }
}

private val districts=listOf(
    Text3("云端觉醒 · 身份中心","クラウド覚醒 · ID センター","Awakening · Identity hub"),Text3("服务器基地 · 数据中心","サーバー基地 · データセンター","Server base · Data center"),
    Text3("网络迷宫 · 网络中心","ネットワーク迷宮 · 網センター","Network maze · Network hub"),Text3("数据守护 · 存储中心","データ守護 · ストレージ","Data guard · Storage hub"),
    Text3("数据库危机 · 数据库中心","DB 危機 · データベース","Database crisis · DB hub"),Text3("流量风暴 · 边缘网关","トラフィック嵐 · エッジ","Traffic storm · Edge gate"),
    Text3("监控中心","監視センター","Monitoring center"),Text3("自动化中心","自動化センター","Automation center"),
    Text3("容器基地","コンテナ基地","Container base"),Text3("最终云战役 · 指挥塔","最終決戦 · 司令塔","Final battle · Command tower"))

/** Chapter i is unlocked when the last mission of the previous chapter is complete. */
private fun chapterUnlocked(w: WorldState,i: Int)=i==0 || w.completed.containsKey("aws-%03d".format(i*6))
private fun chapterStars(w: WorldState,i: Int)=(1..6).sumOf { w.completed["aws-%03d".format(i*6+it)] ?: 0 }

@Composable private fun MapCanvas(w: WorldState,lang: String) {
    var zoom by remember { mutableFloatStateOf(1f) }; var pan by remember { mutableStateOf(Offset.Zero) }
    val labels=rememberTextMeasurer()
    Column {
        Row { TextButton(onClick={zoom=1f;pan=Offset.Zero}) { Text(if(lang=="zh")"重置视图" else if(lang=="ja")"表示リセット" else "Reset view") } }
        Canvas(Modifier.fillMaxWidth().height(300.dp).clipToBounds().background(Color(0xFF0C1C30),RoundedCornerShape(16.dp)).pointerInput(Unit) { detectTransformGestures { _,p,z,_ -> zoom=(zoom*z).coerceIn(0.6f,3f);pan+=p } }) {
            val points=(0..9).map { i -> Offset(size.width*(if(i%2==0)0.18f else 0.58f),40f+i*(size.height-80)/9)*zoom+pan }
            points.zipWithNext().forEachIndexed { i,(a,b) -> drawLine(if(chapterUnlocked(w,i+1))Color(0xFF6CE9D2) else Color(0xFF31536F),a,b,3f) }
            points.forEachIndexed { i,p ->
                val open=chapterUnlocked(w,i); val stars=chapterStars(w,i)
                drawRect(if(open)Color(0xFF14394A) else Color(0xFF1B2533),p-Offset(26f,20f),androidx.compose.ui.geometry.Size(52f,40f))
                drawRect(if(stars==18)Color(0xFFFFC46B) else if(open)Color(0xFF6CE9D2) else Color(0xFF3E5066),p-Offset(26f,20f),androidx.compose.ui.geometry.Size(52f,40f),style=androidx.compose.ui.graphics.drawscope.Stroke(3f))
                val label="${i+1}. ${districts[i].get(lang)}  ${if(open)"★$stars/18" else "🔒"}"
                drawText(labels,label,p+Offset(-26f,24f),style=androidx.compose.ui.text.TextStyle(color=if(open)Color(0xFFE6F4F1) else Color(0xFF7A8899),fontSize=11.sp))
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable private fun Console(vm: GameViewModel,w: WorldState,lang: String,output: String) {
    fun t(zh:String,ja:String,en:String)=when(lang){"zh"->zh;"ja"->ja;else->en}
    val mission=vm.missions.find { it.id==w.missionId } ?: if(w.missionId=="lab") vm.missions[6].copy(id="lab",title=Text3("自由实验室","自由ラボ","Sandbox lab"),story=Text3("独立实验环境，无任务奖励。","独立環境。任務報酬なし。","Independent sandbox. No mission rewards."),objectives=emptyList()) else null
    var search by remember { mutableStateOf("") }; var op by remember { mutableStateOf("create") }; var id by remember { mutableStateOf("") }; var args by remember { mutableStateOf("") }; var policyAction by remember { mutableStateOf("s3:GetObject") }; var policyResource by remember { mutableStateOf("arn:aws:s3:::rescue/*") }
    LazyColumn(verticalArrangement=Arrangement.spacedBy(8.dp)) {
        if(mission==null) { item { Text(t("请先从章节地图开始任务。","マップから任務を開始してください。","Start a mission from the map.")) }; return@LazyColumn }
        item { Text(mission.title.get(lang),style=MaterialTheme.typography.titleLarge);Text(mission.story.get(lang)) }
        val report=MissionValidator().validate(mission,w)
        items(mission.objectives.indices.toList()) { i -> val o=mission.objectives[i]; Text("${if(report.objectives[i])"✓" else "○"} ${o.resource} · ${o.kind} ${o.key} ${o.value} ${o.action} ${if(o.target!="*")o.target else ""}") }
        item { OutlinedTextField(value=search,onValueChange={search=it},label={Text(t("搜索资源 / 类型","リソース検索","Search resource / type"))},modifier=Modifier.fillMaxWidth()) }
        items(w.resources.filter { (it.id+it.type).contains(search,true) },key={it.id}) { r ->
            Card(onClick={id=r.id},modifier=Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) { Text("${r.type.uppercase()} / ${r.id}",color=MaterialTheme.colorScheme.primary);Text(r.attrs.entries.joinToString(" · ") { "${it.key}=${it.value}" },style=MaterialTheme.typography.bodySmall);if(r.policies.isNotEmpty())Text(r.policies.toString(),style=MaterialTheme.typography.bodySmall)
                if(r.type=="instance")Row { listOf("start","stop","reboot","terminate").forEach { action -> TextButton(enabled=if(action=="start")r.attrs["state"]=="stopped" else r.attrs["state"]=="running",onClick={vm.execute(SimulationCommand(action,r.id))}) { Text(action,fontSize=11.sp) } } }
                if(r.rules.isNotEmpty())Text(r.rules.joinToString("\n") { "${it.number} ${it.direction} ${it.protocol} ${it.fromPort}-${it.toPort} ${it.cidr} ${it.effect}" },style=MaterialTheme.typography.bodySmall)
                if(r.routes.isNotEmpty())Text(r.routes.joinToString("\n") { "${it.cidr} → ${it.target}" },style=MaterialTheme.typography.bodySmall)
                if(r.objects.isNotEmpty())Text(r.objects.joinToString("\n") { "${it.key} ${it.version} ${if(it.deleted)"[delete marker]" else it.storageClass} ${it.encryptionKey}" },style=MaterialTheme.typography.bodySmall)
            } }
        }
        item {
            Text(t("操作面板","操作パネル","Actions"),style=MaterialTheme.typography.titleLarge)
            OutlinedTextField(value=op,onValueChange={op=it},label={Text(t("查找 / 选择操作","操作を検索 / 選択","Find / select operation"))},modifier=Modifier.fillMaxWidth())
            Row(Modifier.horizontalScroll(rememberScrollState())) { mission.allowedActions.filter { op.isEmpty() || it.contains(op,true) }.forEach { a -> FilterChip(selected=op==a,onClick={op=a},label={Text(a)},modifier=Modifier.padding(end=4.dp)) } }
            SimulatorRegistry.operations[op]?.let { spec -> Text("${t("必填参数","必須パラメータ","Required parameters")}: ${spec.parameters.ifEmpty { "—" }}\n${t("可选参数","任意パラメータ","Optional parameters")}: ${spec.optional.joinToString().ifEmpty { "—" }}",style=MaterialTheme.typography.bodyMedium) }
            OutlinedTextField(value=id,onValueChange={id=it},label={Text(t("资源 ID","リソース ID","Resource ID"))},modifier=Modifier.fillMaxWidth())
            OutlinedTextField(value=args,onValueChange={args=it},label={Text(t("参数 key=value，每行一项","引数 key=value、1行1項目","Parameters key=value, one per line"))},minLines=2,modifier=Modifier.fillMaxWidth())
            if(op=="policy") { OutlinedTextField(value=policyAction,onValueChange={policyAction=it},label={Text("Action")}); OutlinedTextField(value=policyResource,onValueChange={policyResource=it},label={Text("Resource")}) }
            Text(t("create 参数：type=user / role / instance / volume。实例还需 instanceType、ami、subnet、sg、az。resizeVolume 使用 size；service 使用 app。","create: type=user / role / instance / volume。instance には instanceType、ami、subnet、sg、az。resizeVolume: size、service: app。","create: type=user / role / instance / volume. Instances also need instanceType, ami, subnet, sg, az. resizeVolume: size; service: app."),style=MaterialTheme.typography.bodySmall)
            Button(onClick={ val parsed=args.lines().filter { it.isNotBlank() }.map { it.split('=',limit=2) }; if(parsed.any { it.size!=2 })vm.output.value="Invalid key=value" else vm.execute(SimulationCommand(op,id,parsed.associate { it[0].trim() to it[1].trim() },if(op=="policy")listOf(Policy("Allow",listOf(policyAction),listOf(policyResource))) else emptyList())) }) { Text(t("执行操作","実行","Execute")) }
            Text(output,color=MaterialTheme.colorScheme.primary)
            Text("${t("教学虚拟成本","教育用仮想コスト","Educational cost")}: %.5f · %.3f/h".format(w.cost,CommandBus.hourlyCost(w)))
            Row { Button(onClick={vm.execute(SimulationCommand("claim"))}) { Text(t("检查 / 领取奖励","確認 / 報酬","Check / Claim")) }; TextButton(onClick={vm.execute(SimulationCommand("hint"))}) { Text("Mira (${w.hints}/3)") }; TextButton(onClick={vm.start(mission.id)}) { Text(t("重置","リセット","Reset")) } }
            if(w.hints>0)Text(mission.hints[(w.hints-1).coerceAtMost(2)].get(lang))
            if(report.passed) { Text("${t("任务完成","任務完了","Mission complete")} · ${report.score}/100 ${"★".repeat(report.stars)}"); Text(mission.lesson.get(lang)); if(w.completed.containsKey(mission.id) && mission.id!="lab" && mission.order<vm.missions.size)Button(onClick={vm.start("aws-%03d".format(mission.order+1))}) { Text(t("下一关","次へ","Next mission")) } }
            Text(t("监控 / 通知","監視 / 通知","Monitoring / notifications"),style=MaterialTheme.typography.titleMedium)
            Telemetry(w,id.ifEmpty { "i-web" })
            w.resources.filter { it.type=="alarm" }.forEach { Text("${it.id}: ${it.attrs["state"]} · ${it.attrs["metric"]} ${it.attrs["comparison"]} ${it.attrs["threshold"]}") }
            w.notifications.takeLast(5).forEach { Text("${it.tick} · SNS ${it.topic} · ${it.alarm}: ${it.message}") }
            w.observations.takeLast(3).forEach { Text("${it.tick} · ${it.kind} ${it.resource}\n${it.detail}",style=MaterialTheme.typography.bodySmall) }
            Text(t("审计事件","監査イベント","Audit events"),style=MaterialTheme.typography.titleMedium)
            w.events.takeLast(8).reversed().forEach { Text("${it.tick} · ${it.identity} · ${it.command.op} ${it.command.id} · ${it.message}",style=MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable private fun Terminal(vm: GameViewModel,lang: String,output: String) {
    var input by remember { mutableStateOf("aws ec2 describe-instances") }; var transcript by remember { mutableStateOf("") }; val history=remember { mutableStateListOf<String>() }; var index by remember { mutableIntStateOf(0) }; val clipboard=LocalClipboardManager.current
    LaunchedEffect(output) { if(output.isNotBlank())transcript+="\n$output\n" }
    Column(Modifier.fillMaxSize()) {
        Text("CDA LOCAL TERMINAL / NO SHELL",color=MaterialTheme.colorScheme.primary)
        Text(transcript,fontFamily=FontFamily.Monospace,fontSize=12.sp,modifier=Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceVariant).padding(12.dp))
        OutlinedTextField(value=input,onValueChange={input=it},label={Text("aws / sim")},modifier=Modifier.fillMaxWidth())
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            Button(onClick={transcript+="\n$ $input";history+=input;index=history.size;vm.cli(input)}) { Text(if(lang=="zh")"执行" else if(lang=="ja")"実行" else "Run") }
            TextButton(onClick={if(index>0){index--;input=history[index]}}) { Text("↑") };TextButton(onClick={if(index<history.lastIndex){index++;input=history[index]}}) { Text("↓") }
            TextButton(onClick={clipboard.setText(AnnotatedString(transcript))}) { Text(if(lang=="zh")"复制" else if(lang=="ja")"コピー" else "Copy") }
            TextButton(onClick={transcript=""}) { Text(if(lang=="zh")"清空" else if(lang=="ja")"消去" else "Clear") }; TextButton(onClick={input="aws help";vm.cli(input)}) { Text("help") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState())) { listOf("aws ec2 describe-instances","aws iam list-users","aws cloudwatch list-metrics","sim growFilesystem vol-data").filter { it.startsWith(input) || input.isEmpty() }.forEach { suggestion -> AssistChip(onClick={input=suggestion},label={Text(suggestion)}) } }
    }
}
