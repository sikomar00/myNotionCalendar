package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.DateValue
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.ExtraProp
import com.ysdoing.notioncal.core.data.PropValue
import com.ysdoing.notioncal.core.data.SelectValue
import com.ysdoing.notioncal.core.data.ledger.Flow
import com.ysdoing.notioncal.core.notion.mapping.LedgerWrite
import com.ysdoing.notioncal.core.notion.mapping.PageWrite
import com.ysdoing.notioncal.core.notion.mapping.RemoteEvent
import com.ysdoing.notioncal.core.notion.mapping.WriteDate
import java.time.Instant
import java.time.LocalDate

// 아웃박스 바꿈(EventChange)을 비교·변환하는 도움 함수. OutboxProcessor가 500줄을 넘지 않게 나눴다.

/** 충돌을 판정하는 필드 단위(PRD §8.5-6: 속성 단위). */
internal enum class ChangeField {
    TITLE, DATE, DONE, SELECT;

    fun valueOf(change: EventChange): Any? = when (this) {
        TITLE -> change.title
        DATE -> change.date
        DONE -> change.done
        SELECT -> change.select
    }

    fun drop(change: EventChange): EventChange = when (this) {
        TITLE -> change.copy(title = null)
        DATE -> change.copy(date = null)
        DONE -> change.copy(done = null)
        SELECT -> change.copy(select = null)
    }

    fun describe(change: EventChange): String? = when (this) {
        TITLE -> change.title
        DATE -> change.date?.let { d -> listOfNotNull(d.startDate, d.endDate).joinToString("~") }
        DONE -> change.done?.toString()
        SELECT -> change.select?.optionId
    }
}

/** 내가 바꾼 필드 중 노션에서도 기준값과 달라진 필드. */
internal fun conflictingFields(change: EventChange, base: EventChange, remote: EventChange): List<ChangeField> =
    ChangeField.entries.filter { field ->
        // 노션 값이 이미 내 값과 같으면(끊기기 전에 노션이 받은 편집을 재시도) 충돌이 아니다.
        field.valueOf(change) != null && field.valueOf(remote) != field.valueOf(base) && field.valueOf(remote) != field.valueOf(change)
    }

/** 서버 일정에서 [change]와 같은 필드만 같은 모양으로 꺼낸다. */
internal fun RemoteEvent.valuesFor(change: EventChange) = EventChange(
    title = title.takeIf { change.title != null },
    date = DateValue(
        startDate.toString(), endDate?.toString(), startTime?.toEpochMilli(), endTime?.toEpochMilli(),
    ).takeIf { change.date != null },
    done = done.takeIf { change.done != null },
    select = SelectValue(colorOptionId).takeIf { change.select != null },
    props = change.props?.let { wanted -> wanted.keys.associateWith { extraValues[it]?.toPropValue() ?: PropValue() } },
)

/** EX-07: 내가 바꾼 추가 속성 중 노션에서도 기준값과 달라진 것(속성 ID). */
internal fun conflictingProps(change: EventChange, base: EventChange, remote: EventChange): List<String> =
    change.props.orEmpty().keys.filter { id ->
        val now = remote.props?.get(id) ?: PropValue()
        now != (base.props?.get(id) ?: PropValue()) && now != change.props?.get(id)
    }

internal fun PropValue.describe(): String =
    optionIds?.joinToString(",") ?: number?.toString() ?: text.orEmpty()

internal fun EventChange.toPageWrite(catalog: List<ExtraProp> = emptyList()) = PageWrite(
    title = title,
    date = date?.let { d ->
        WriteDate(
            startDate = LocalDate.parse(d.startDate),
            endDate = d.endDate?.let(LocalDate::parse),
            start = d.startTime?.let(Instant::ofEpochMilli),
            end = d.endTime?.let(Instant::ofEpochMilli),
        )
    },
    done = done,
    setSelect = select != null,
    selectOptionId = select?.optionId,
    props = props.orEmpty().toPropertyWrites(catalog),
    link = link,
    ledger = ledger?.let { tx ->
        LedgerWrite(
            amount = tx.amount,
            flow = tx.flow?.let { name -> Flow.entries.firstOrNull { it.name == name }?.notionName },
            category = tx.category,
            time = tx.time,
            planned = tx.planned,
            manual = tx.manual,
        )
    },
)

/** [edit]에 든 필드만 남긴다(그 필드의 기준값으로 쓰려고). */
internal fun EventChange.restrictedTo(edit: EventChange) = EventChange(
    title = title.takeIf { edit.title != null },
    date = date.takeIf { edit.date != null },
    done = done.takeIf { edit.done != null },
    select = select.takeIf { edit.select != null },
    props = props?.filterKeys { edit.props?.containsKey(it) == true },
    link = link.takeIf { edit.link != null },
    ledger = ledger.takeIf { edit.ledger != null },
)

/** [old]와 다른 필드만 남긴다(생성을 보낸 뒤 고친 것만 이어 보내려고). */
internal fun EventChange.changedFrom(old: EventChange) = EventChange(
    title = title.takeIf { it != old.title },
    date = date.takeIf { it != old.date },
    done = done.takeIf { it != old.done },
    select = select.takeIf { it != old.select },
    props = props?.filter { (id, value) -> old.props?.get(id) != value }?.takeIf { it.isNotEmpty() },
    link = link.takeIf { it != old.link },
    ledger = ledger.takeIf { it != old.ledger },
)
