package com.ysdoing.notioncal.core.designsystem

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

// UX-02·03(부록 A-4·5): 켜고 끄는·고르는 줄은 줄 전체가 눌림 영역(최소 48dp)이고, 줄의 글자가 컨트롤의 이름으로 낭독된다.
// 안쪽 컨트롤은 onChange·onClick을 null로 둔다(줄이 받는다). 컨트롤을 직접 두어야 하는 줄은 아래 두 Modifier를 쓴다.

/** 줄 전체를 켜고 끄는 영역으로 만든다(최소 높이 48dp). */
fun Modifier.toggleRow(checked: Boolean, role: Role = Role.Switch, onChange: (Boolean) -> Unit): Modifier =
    heightIn(min = 48.dp).toggleable(value = checked, role = role, onValueChange = onChange)

/** 줄 전체를 라디오 선택 영역으로 만든다(최소 높이 48dp). */
fun Modifier.selectableRow(selected: Boolean, onClick: () -> Unit): Modifier =
    heightIn(min = 48.dp).selectable(selected = selected, role = Role.RadioButton, onClick = onClick)

/** 켜고 끄는 한 줄: 라벨(+ 짧은 설명)과 스위치. */
@Composable
fun SwitchRow(label: String, checked: Boolean, modifier: Modifier = Modifier, note: String? = null, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().toggleRow(checked, onChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label)
            if (note != null) Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** 체크하는 한 줄: 체크박스와 라벨. */
@Composable
fun CheckboxRow(label: String, checked: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.fillMaxWidth().toggleRow(checked, Role.Checkbox, onChange), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}

/** 하나만 고르는 한 줄: 라디오 버튼과 라벨. */
@Composable
fun RadioRow(label: String, selected: Boolean, modifier: Modifier = Modifier, onSelect: () -> Unit) {
    Row(modifier.fillMaxWidth().selectableRow(selected, onSelect), verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
