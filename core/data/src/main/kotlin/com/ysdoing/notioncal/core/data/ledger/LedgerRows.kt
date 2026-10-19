package com.ysdoing.notioncal.core.data.ledger

import com.ysdoing.notioncal.core.data.db.BudgetEntity
import com.ysdoing.notioncal.core.data.db.EventEntity

/** Room 행 → 계산용 거래. 금액이 없는 행(가계부 매핑 전 캐시 등)은 0원으로 본다. */
fun EventEntity.toLedgerTx() = LedgerTx(
    pageId = pageId,
    date = startDate,
    time = txTime,
    amount = amount ?: 0,
    flow = flow?.let { name -> Flow.entries.firstOrNull { it.name == name } },
    category = category,
    title = title,
    payment = payment,
    planned = planned,
    manual = manual,
    createdAt = createdTime,
)

fun BudgetEntity.toBudgetRow() = BudgetRow(periodType, periodKey, groupName, amount)
