package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.BudgetPropIds
import com.ysdoing.notioncal.core.data.CategoryPropIds
import com.ysdoing.notioncal.core.data.LedgerPropIds
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto

/** LD-01 거래내역 매핑(속성 ID). 금액(number)·타입(select)은 필수, 나머지는 비울 수 있다. */
data class LedgerChoice(
    val titlePropId: String,
    val datePropId: String,
    val amount: String,
    val flow: String,
    val category: String?,
    val time: String?,
    val payment: String?,
    val planned: String?,
    val manual: String?,
)

/** LD-05 예산 매핑. 기간유형·기간값은 필수. */
data class BudgetChoice(
    val titlePropId: String,
    val periodType: String,
    val periodValue: String,
    val group: String?,
    val amount: String?,
)

/** 카테고리 마스터 매핑. 제목이 대분류다. */
data class CategoryChoice(val titlePropId: String, val group: String?)

/** 거래내역으로 쓸 수 없는 이유(LD-01: 등록을 막고 보여 준다). */
enum class LedgerProblem { NO_DATE, AMOUNT_NOT_NUMBER, NO_TYPE_SELECT }

/** 예산·카테고리 마스터를 조회한 결과(등록 화면의 후보·추천값). */
data class CompanionInspection(
    val target: HabitTarget,
    val candidates: LedgerCandidates,
    val budget: BudgetChoice?,
    val category: CategoryChoice?,
    /** 예산 '그룹' 선택지(LD-01 예산 그룹 고르기). */
    val groupOptions: List<String> = emptyList(),
)

/** 등록 화면의 속성 후보(유형별). */
data class LedgerCandidates(
    val titles: List<PropertyOption>,
    val dates: List<PropertyOption>,
    val numbers: List<PropertyOption>,
    val selects: List<PropertyOption>,
    val texts: List<PropertyOption>,
    val checkboxes: List<PropertyOption>,
)

/**
 * LD-01 추천 매핑(PRD §8.4 프리셋). 이름이 맞을 때만 고른다. 금액이 number가 아니면 등록을 막는다.
 * 예산·카테고리 마스터는 거래내역과 같은 페이지의 자식 DB 중 제목으로 찾는다(못 찾으면 사용자가 링크를 넣는다).
 */
object LedgerSuggester {
    private val amountPreset = listOf("금액", "Amount")
    private val flowPreset = listOf("타입", "유형", "Type")
    private val categoryPreset = listOf("대분류", "카테고리", "Category")
    private val timePreset = listOf("시간", "Time")
    private val paymentPreset = listOf("결제수단", "Payment")
    private val plannedPreset = listOf("예정", "Planned")
    private val manualPreset = listOf("앱입력", "App")
    private val datePreset = listOf("날짜", "Date")
    private val periodTypePreset = listOf("기간유형")
    private val periodValuePreset = listOf("기간값")
    private val groupPreset = listOf("그룹", "Group")
    private val budgetAmountPreset = listOf("예산금액", "금액", "Amount")

    private fun DataSourceDto.options(vararg types: String) =
        properties.values.filter { it.type in types }.map { PropertyOption(it.id, it.name, it.type) }.sortedBy { it.name }

    private fun List<PropertyOption>.byPreset(names: List<String>): PropertyOption? =
        names.firstNotNullOfOrNull { name -> firstOrNull { it.name.trim().equals(name, ignoreCase = true) } }

    fun candidates(schema: DataSourceDto) = LedgerCandidates(
        titles = schema.options("title"),
        dates = schema.options("date"),
        numbers = schema.options("number"),
        selects = schema.options("select"),
        texts = schema.options("rich_text"),
        checkboxes = schema.options("checkbox"),
    )

    fun problem(schema: DataSourceDto): LedgerProblem? {
        if (schema.options("date").isEmpty()) return LedgerProblem.NO_DATE
        val all = schema.options("title", "date", "number", "select", "rich_text", "checkbox", "formula", "multi_select", "status", "url")
        val named = all.byPreset(amountPreset)
        if (schema.options("number").isEmpty() || (named != null && named.type != "number")) return LedgerProblem.AMOUNT_NOT_NUMBER
        if (schema.options("select").byPreset(flowPreset) == null) return LedgerProblem.NO_TYPE_SELECT
        return null
    }

    fun suggestLedger(schema: DataSourceDto): LedgerChoice? {
        if (problem(schema) != null) return null
        val c = candidates(schema)
        val title = c.titles.firstOrNull() ?: return null
        val date = c.dates.byPreset(datePreset) ?: c.dates.first()
        val amount = c.numbers.byPreset(amountPreset) ?: return null
        val flow = c.selects.byPreset(flowPreset) ?: return null
        return LedgerChoice(
            titlePropId = title.id,
            datePropId = date.id,
            amount = amount.id,
            flow = flow.id,
            category = c.selects.byPreset(categoryPreset)?.id,
            time = c.texts.byPreset(timePreset)?.id,
            payment = c.selects.byPreset(paymentPreset)?.id,
            planned = c.checkboxes.byPreset(plannedPreset)?.id,
            manual = c.checkboxes.byPreset(manualPreset)?.id,
        )
    }

    fun suggestBudget(schema: DataSourceDto): BudgetChoice? {
        val c = candidates(schema)
        return BudgetChoice(
            titlePropId = c.titles.firstOrNull()?.id ?: return null,
            periodType = c.selects.byPreset(periodTypePreset)?.id ?: return null,
            periodValue = c.texts.byPreset(periodValuePreset)?.id ?: return null,
            group = c.selects.byPreset(groupPreset)?.id,
            amount = c.numbers.byPreset(budgetAmountPreset)?.id,
        )
    }

    fun suggestCategory(schema: DataSourceDto): CategoryChoice? {
        val c = candidates(schema)
        return CategoryChoice(c.titles.firstOrNull()?.id ?: return null, c.selects.byPreset(groupPreset)?.id)
    }

    /** select 속성의 옵션 이름(예산 그룹 고르기). */
    fun groupOptions(schema: DataSourceDto, groupPropId: String?): List<String> =
        schema.properties.values.firstOrNull { it.id == groupPropId }?.select?.options.orEmpty().map { it.name }

    /** 자식 DB 목록 → (예산 DB ID, 카테고리 마스터 DB ID). 제목이 정확히 '예산'인 것을 먼저, 없으면 포함하는 것. */
    fun pickCompanions(children: List<Pair<String, String>>): Pair<String?, String?> {
        fun pick(exact: String, contains: String) =
            children.firstOrNull { it.second.trim() == exact }?.first ?: children.firstOrNull { contains in it.second }?.first
        return pick("예산", "예산") to pick("카테고리 마스터", "카테고리")
    }
}

/**
 * LD-01 등록 행 세 개(거래내역 LEDGER, 예산 BUDGET, 카테고리 마스터 CATEGORY). 예산·카테고리는 linkDataSourceId로
 * 거래내역을 가리켜 함께 켜고 지운다. 다시 등록하면 켜기·위치는 기존 행에서 옮기고, 매핑이 바뀐 행만 처음부터 다시 받는다.
 */
internal fun ledgerRows(
    ledger: HabitTarget,
    ledgerChoice: LedgerChoice,
    budget: HabitTarget,
    budgetChoice: BudgetChoice,
    category: HabitTarget,
    categoryChoice: CategoryChoice,
    budgetGroup: String,
    spendNegative: Boolean,
    colorHex: String,
    existing: Map<String, DataSourceEntity>,
    nextPosition: Int,
): Triple<DataSourceEntity, DataSourceEntity, DataSourceEntity> {
    fun row(target: HabitTarget, kind: SourceKind, title: String, date: String?, props: String, link: String?, offset: Int): DataSourceEntity {
        val old = existing[target.dataSourceId]
        val changed = old == null || old.titlePropId != title || old.datePropId != date || old.rolePropIds != props || old.kind != kind.name
        return DataSourceEntity(
            dataSourceId = target.dataSourceId,
            databaseId = target.databaseId,
            name = target.name,
            colorHex = colorHex,
            titlePropId = title,
            datePropId = date,
            donePropId = null,
            doneType = null,
            doneOption = null,
            undoneOption = null,
            colorPropId = null,
            sortPropId = null,
            enabled = old?.enabled ?: true,
            lastSyncAt = old?.lastSyncAt?.takeUnless { changed },
            lastFullSyncAt = old?.lastFullSyncAt?.takeUnless { changed },
            position = old?.position ?: (nextPosition + offset),
            propCatalog = old?.propCatalog,
            mappedPropNames = old?.mappedPropNames?.takeUnless { changed },
            schemaIssues = null,
            kind = kind.name,
            linkDataSourceId = link,
            rolePropIds = props,
            spendNegative = spendNegative,
            budgetGroup = budgetGroup,
        )
    }
    val ledgerProps = LedgerPropIds.encode(
        LedgerPropIds(ledgerChoice.amount, ledgerChoice.flow, ledgerChoice.category, ledgerChoice.time, ledgerChoice.payment, ledgerChoice.planned, ledgerChoice.manual),
    )
    val budgetProps = BudgetPropIds.encode(BudgetPropIds(budgetChoice.periodType, budgetChoice.periodValue, budgetChoice.group, budgetChoice.amount))
    val categoryProps = CategoryPropIds.encode(CategoryPropIds(categoryChoice.group))
    return Triple(
        row(ledger, SourceKind.LEDGER, ledgerChoice.titlePropId, ledgerChoice.datePropId, ledgerProps, null, 0),
        row(budget, SourceKind.BUDGET, budgetChoice.titlePropId, null, budgetProps, ledger.dataSourceId, 1),
        row(category, SourceKind.CATEGORY, categoryChoice.titlePropId, null, categoryProps, ledger.dataSourceId, 2),
    )
}
