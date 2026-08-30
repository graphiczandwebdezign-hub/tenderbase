package com.tenderbase.app

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.android.material.button.MaterialButton
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * Advanced filter panel (bottom sheet on phones).
 *
 * Receives the currently-applied filters plus facet options (with counts) as
 * JSON arguments, and returns the new selection via setFragmentResult so no
 * activity references are held across configuration changes.
 */
class FilterBottomSheet : BottomSheetDialogFragment() {

    companion object {
        const val REQUEST_KEY = "filter_request"
        const val ARG_CURRENT = "current_filters"
        const val ARG_FACETS = "facets"
        const val RESULT_FILTERS = "filters_json"

        fun create(current: SearchFilters, facetsJson: String?): FilterBottomSheet {
            return FilterBottomSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CURRENT, current.toJson())
                    putString(ARG_FACETS, facetsJson)
                }
            }
        }

        /** Serialize facets for the sheet (options come from live data only). */
        fun facetsToJson(f: ApiClient.Facets?): String? {
            if (f == null) return null
            val o = JSONObject()
            o.put("provinces", facetArray(f.provinces))
            o.put("categories", facetArray(f.categories))
            o.put("sources", facetArray(f.sources))
            return o.toString()
        }

        private fun facetArray(items: List<ApiClient.FacetItem>): JSONArray {
            val arr = JSONArray()
            for (i in items) arr.put(JSONObject().put("name", i.name).put("count", i.count))
            return arr
        }
    }

    private var current: SearchFilters = SearchFilters()
    private var customStartIso: String? = null
    private var customEndIso: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.sheet_filters, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        current = SearchFilters.fromJson(arguments?.getString(ARG_CURRENT))
        customStartIso = current.closingAfter
        customEndIso = current.closingBefore
        val facetsJson = arguments?.getString(ARG_FACETS)

        val statusGroup = view.findViewById<ChipGroup>(R.id.statusGroup)
        val dateGroup = view.findViewById<ChipGroup>(R.id.dateGroup)
        val provinceGroup = view.findViewById<ChipGroup>(R.id.provinceGroup)
        val categoryGroup = view.findViewById<ChipGroup>(R.id.categoryGroup)
        val sourceGroup = view.findViewById<ChipGroup>(R.id.sourceGroup)
        val customDateRow = view.findViewById<View>(R.id.customDateRow)
        val startBtn = view.findViewById<MaterialButton>(R.id.startDateButton)
        val endBtn = view.findViewById<MaterialButton>(R.id.endDateButton)

        // ---- Status (single-select, derived lifecycle aliases) ----
        listOf(
            null to getString(R.string.status_any),
            StatusFilter.OPEN to getString(R.string.status_open),
            StatusFilter.CLOSING_SOON to getString(R.string.status_closing_soon),
            StatusFilter.CLOSED to getString(R.string.status_closed)
        ).forEach { (value, label) ->
            statusGroup.addView(
                optionChip(label, value?.key, checked = current.status == value)
            )
        }

        // ---- Date windows (single-select) ----
        listOf(
            DateFilter.ANY to getString(R.string.date_any),
            DateFilter.PUBLISHED_TODAY to getString(R.string.published_today),
            DateFilter.PUBLISHED_7D to getString(R.string.published_7d),
            DateFilter.PUBLISHED_30D to getString(R.string.published_30d),
            DateFilter.CLOSING_7D to getString(R.string.closing_7d),
            DateFilter.CLOSING_14D to getString(R.string.closing_14d),
            DateFilter.CLOSING_30D to getString(R.string.closing_30d),
            DateFilter.CLOSING_CUSTOM to getString(R.string.closing_custom)
        ).forEach { (value, label) ->
            dateGroup.addView(
                optionChip(label, value.key, checked = current.dateFilter == value)
            )
        }
        customDateRow.visibility =
            if (selectedDateFilter(dateGroup) == DateFilter.CLOSING_CUSTOM) View.VISIBLE else View.GONE
        dateGroup.setOnCheckedStateChangeListener { _, _ ->
            customDateRow.visibility =
                if (selectedDateFilter(dateGroup) == DateFilter.CLOSING_CUSTOM) View.VISIBLE else View.GONE
        }
        updateDateButton(startBtn, customStartIso, R.string.start_date)
        updateDateButton(endBtn, customEndIso, R.string.end_date)
        startBtn.setOnClickListener { pickDate(isStart = true) }
        endBtn.setOnClickListener { pickDate(isStart = false) }

        // ---- Facet-driven groups (values that actually exist in the data) ----
        fillFacetGroup(provinceGroup, facetsJson, "provinces", current.provinces)
        fillFacetGroup(categoryGroup, facetsJson, "categories", current.categories)
        val sourceSection = view.findViewById<View>(R.id.sourceSection)
        val sources = facetItems(facetsJson, "sources")
        if (sources.size < 2) {
            sourceSection.visibility = View.GONE
        } else {
            sourceSection.visibility = View.VISIBLE
            fillFacetGroup(sourceGroup, facetsJson, "sources", current.sources)
        }

        // ---- Reset / apply ----
        view.findViewById<Button>(R.id.resetButton).setOnClickListener { reset() }
        view.findViewById<Button>(R.id.applyButton).setOnClickListener { apply() }
    }

    // ------------------------------------------------------------------ chips

    private fun optionChip(label: String, tag: String?, checked: Boolean): Chip =
        Chip(requireContext()).apply {
            text = label
            isCheckable = true
            isChecked = checked
            this.tag = tag
            setEnsureMinTouchTargetSize(true)
        }

    private fun facetChip(name: String, count: Int, checked: Boolean): Chip =
        Chip(requireContext()).apply {
            text = if (count > 0) "$name ($count)" else name
            isCheckable = true
            isChecked = checked
            tag = name
            setEnsureMinTouchTargetSize(true)
        }

    private fun fillFacetGroup(
        group: ChipGroup, facetsJson: String?, key: String, selected: List<String>
    ) {
        for (item in facetItems(facetsJson, key)) {
            group.addView(facetChip(item.first, item.second, item.first in selected))
        }
        if (group.childCount == 0) {
            group.visibility = View.GONE
        }
    }

    private fun facetItems(facetsJson: String?, key: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        if (facetsJson == null) return out
        try {
            val arr = JSONObject(facetsJson).optJSONArray(key) ?: return out
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = o.optString("name")
                if (name.isNotEmpty()) out.add(name to o.optInt("count"))
            }
        } catch (_: Exception) {
        }
        return out
    }

    // ------------------------------------------------------------------ state

    private fun selectedDateFilter(group: ChipGroup): DateFilter {
        val id = group.checkedChipId
        if (id == View.NO_ID) return DateFilter.ANY
        val tag = group.findViewById<Chip>(id)?.tag as? String ?: return DateFilter.ANY
        return DateFilter.fromKey(tag)
    }

    private fun selectedStatus(group: ChipGroup): StatusFilter? {
        val id = group.checkedChipId
        if (id == View.NO_ID) return null
        val tag = group.findViewById<Chip>(id)?.tag as? String ?: return null
        // The "Any status" chip carries a null tag -> no status filter.
        return StatusFilter.fromKey(tag)
    }

    private fun checkedValues(group: ChipGroup): List<String> {
        val out = ArrayList<String>()
        for (id in group.checkedChipIds) {
            (group.findViewById<Chip>(id))?.tag?.let { out.add(it as String) }
        }
        return out
    }

    private fun updateDateButton(button: MaterialButton, iso: String?, placeholderRes: Int) {
        button.text = iso ?: getString(placeholderRes)
    }

    private fun pickDate(isStart: Boolean) {
        val picker = MaterialDatePicker.Builder.datePicker()
            .setTitleText(if (isStart) R.string.start_date else R.string.end_date)
            .setSelection(
                (if (isStart) customStartIso else customEndIso)?.let { utcMillis(it) }
                    ?: MaterialDatePicker.todayInUtcMilliseconds()
            )
            .build()
        picker.addOnPositiveButtonClickListener { millis ->
            val iso = isoFromUtcMillis(millis)
            if (isStart) {
                customStartIso = iso
                view?.findViewById<MaterialButton>(R.id.startDateButton)?.let {
                    updateDateButton(it, iso, R.string.start_date)
                }
            } else {
                customEndIso = iso
                view?.findViewById<MaterialButton>(R.id.endDateButton)?.let {
                    updateDateButton(it, iso, R.string.end_date)
                }
            }
            // Keep the range sane: start must not be after end.
            val s = customStartIso
            val e = customEndIso
            if (s != null && e != null && s > e) {
                if (isStart) customEndIso = s else customStartIso = e
            }
        }
        picker.show(childFragmentManager, if (isStart) "startPicker" else "endPicker")
    }

    private fun utcMillis(iso: String): Long {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return try {
            fmt.parse(iso)?.time ?: MaterialDatePicker.todayInUtcMilliseconds()
        } catch (_: Exception) {
            MaterialDatePicker.todayInUtcMilliseconds()
        }
    }

    private fun isoFromUtcMillis(millis: Long): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        fmt.timeZone = TimeZone.getTimeZone("UTC")
        return fmt.format(java.util.Date(millis))
    }

    // ---------------------------------------------------------------- actions

    private fun reset() {
        current = SearchFilters(query = current.query, sort = current.sort)
        customStartIso = null
        customEndIso = null
        // Rebuild the sheet selections.
        view?.let { v ->
            v.findViewById<ChipGroup>(R.id.statusGroup).clearCheck()
            val dateGroup = v.findViewById<ChipGroup>(R.id.dateGroup)
            dateGroup.clearCheck()
            v.findViewById<ChipGroup>(R.id.provinceGroup).clearCheck()
            v.findViewById<ChipGroup>(R.id.categoryGroup).clearCheck()
            v.findViewById<ChipGroup>(R.id.sourceGroup).clearCheck()
            v.findViewById<View>(R.id.customDateRow).visibility = View.GONE
            v.findViewById<MaterialButton>(R.id.startDateButton)?.let {
                updateDateButton(it, null, R.string.start_date)
            }
            v.findViewById<MaterialButton>(R.id.endDateButton)?.let {
                updateDateButton(it, null, R.string.end_date)
            }
        }
    }

    private fun apply() {
        val v = view ?: return dismissAllowingStateLoss()
        var dateFilter = selectedDateFilter(v.findViewById(R.id.dateGroup))
        // A custom range with no bounds chosen filters nothing — treat as any.
        if (dateFilter == DateFilter.CLOSING_CUSTOM && customStartIso == null && customEndIso == null) {
            dateFilter = DateFilter.ANY
        }
        val result = current.copy(
            provinces = checkedValues(v.findViewById(R.id.provinceGroup)),
            categories = checkedValues(v.findViewById(R.id.categoryGroup)),
            sources = checkedValues(v.findViewById(R.id.sourceGroup)),
            status = selectedStatus(v.findViewById(R.id.statusGroup)),
            dateFilter = dateFilter,
            closingAfter = customStartIso,
            closingBefore = customEndIso
        )
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            Bundle().apply { putString(RESULT_FILTERS, result.toJson()) }
        )
        dismiss()
    }
}
