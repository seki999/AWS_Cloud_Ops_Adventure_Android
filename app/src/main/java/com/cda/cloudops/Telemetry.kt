package com.cda.cloudops

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cda.engine.*

/** CloudWatch-style line chart over the deterministic samples recorded by SimulationRuntime.advance. */
@Composable fun Telemetry(w: WorldState,preferred: String) {
    val sampled=w.metrics.map { it.resource }.distinct()
    if(sampled.isEmpty()) { Text("No samples yet · advanceTime or run any command",style=MaterialTheme.typography.bodySmall); return }
    var metric by remember { mutableStateOf("CPU") }
    val resource=if(preferred in sampled) preferred else sampled.first()
    val series=w.metrics.filter { it.resource==resource && it.metric==metric }.takeLast(60)
    Row(Modifier.horizontalScroll(rememberScrollState())) {
        SimulationRuntime.metricNames.forEach { name -> FilterChip(selected=metric==name,onClick={metric=name},label={Text(name)},modifier=Modifier.padding(end=4.dp)) }
    }
    val latest=series.lastOrNull()?.value
    Text("$resource · $metric · ${latest?.let { "%.2f".format(it) } ?: "—"} (${series.size} samples)",style=MaterialTheme.typography.labelMedium)
    val line=MaterialTheme.colorScheme.primary
    Canvas(Modifier.fillMaxWidth().height(120.dp).background(MaterialTheme.colorScheme.surfaceVariant,RoundedCornerShape(12.dp)).semantics { contentDescription="$metric chart for $resource, latest ${latest ?: "none"}" }) {
        if(series.size<2) return@Canvas
        val max=series.maxOf { it.value }.coerceAtLeast(1.0); val pad=12f
        val points=series.mapIndexed { i,s -> Offset(pad+i*(size.width-2*pad)/(series.size-1),size.height-pad-(s.value/max*(size.height-2*pad)).toFloat()) }
        drawLine(Color(0x553E5066),Offset(pad,size.height-pad),Offset(size.width-pad,size.height-pad),2f)
        points.zipWithNext().forEach { (a,b) -> drawLine(line,a,b,4f) }
        drawCircle(line,6f,points.last())
    }
}
