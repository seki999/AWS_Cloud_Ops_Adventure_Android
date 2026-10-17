package com.cda.cloudops

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.cda.engine.*

@Composable fun Topology(vm: GameViewModel,w: WorldState) {
    val nodes=w.resources.filter { it.type in listOf("vpc","subnet","instance","volume") }
    var selected by remember { mutableStateOf<String?>(null) }
    var dragging by remember { mutableStateOf(Offset.Zero) }
    fun position(r: Resource,index: Int)=Offset(r.attrs["x"]?.toFloatOrNull() ?: (80f+(index%3)*200f),r.attrs["y"]?.toFloatOrNull() ?: (80f+(index/3)*180f))
    Text(nodes.joinToString(" · ") { it.id })
    Canvas(Modifier.fillMaxWidth().height(320.dp).pointerInput(nodes) {
        detectDragGestures(onDragStart={ point -> val hit=nodes.withIndex().minByOrNull { (i,r) -> (position(r,i)-point).getDistance() }; if(hit!=null && (position(hit.value,hit.index)-point).getDistance()<50f) {selected=hit.value.id;dragging=position(hit.value,hit.index)} },onDragEnd={ selected?.let { vm.execute(SimulationCommand("position",it,mapOf("x" to dragging.x.toString(),"y" to dragging.y.toString()))) };selected=null },onDrag={ change,amount -> if(selected!=null) { change.consume();dragging+=amount } })
    }) {
        fun point(i: Int,r: Resource)=if(selected==r.id)dragging else position(r,i)
        nodes.forEachIndexed { i,r -> r.attrs.filterKeys { it in listOf("vpc","subnet","instance") }.values.forEach { target -> val j=nodes.indexOfFirst { it.id==target };if(j>=0)drawLine(Color(0xFF6A8CA4),point(i,r),point(j,nodes[j]),4f) } }
        nodes.forEachIndexed { i,r -> drawCircle(if(r.type=="instance" && r.attrs["state"]!="running")Color(0xFFFFB66B) else Color(0xFF40BFA9),28f,point(i,r)) }
    }
}
