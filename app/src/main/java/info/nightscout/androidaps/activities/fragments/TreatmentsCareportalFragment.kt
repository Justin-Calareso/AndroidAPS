package info.nightscout.androidaps.activities.fragments

import android.os.Bundle
import android.view.*
import android.view.ActionMode
import androidx.appcompat.widget.Toolbar
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import dagger.android.support.DaggerFragment
import info.nightscout.androidaps.R
import info.nightscout.androidaps.database.AppRepository
import info.nightscout.androidaps.database.entities.ValueWithUnit
import info.nightscout.androidaps.database.entities.TherapyEvent
import info.nightscout.androidaps.database.entities.UserEntry.Action
import info.nightscout.androidaps.database.entities.UserEntry.Sources
import info.nightscout.androidaps.database.transactions.InvalidateAAPSStartedTherapyEventTransaction
import info.nightscout.androidaps.database.transactions.InvalidateTherapyEventTransaction
import info.nightscout.androidaps.databinding.TreatmentsCareportalFragmentBinding
import info.nightscout.androidaps.databinding.TreatmentsCareportalItemBinding
import info.nightscout.androidaps.events.EventTherapyEventChange
import info.nightscout.shared.logging.AAPSLogger
import info.nightscout.shared.logging.LTag
import info.nightscout.androidaps.logging.UserEntryLogger
import info.nightscout.androidaps.plugins.bus.RxBus
import info.nightscout.androidaps.plugins.general.nsclient.events.EventNSClientRestart
import info.nightscout.androidaps.events.EventTreatmentUpdateGui
import info.nightscout.androidaps.activities.fragments.TreatmentsCareportalFragment.RecyclerViewAdapter.TherapyEventsViewHolder
import info.nightscout.androidaps.utils.DateUtil
import info.nightscout.androidaps.utils.FabricPrivacy
import info.nightscout.androidaps.utils.T
import info.nightscout.androidaps.utils.Translator
import info.nightscout.androidaps.utils.alertDialogs.OKDialog
import info.nightscout.androidaps.utils.buildHelper.BuildHelper
import info.nightscout.androidaps.extensions.toVisibility
import info.nightscout.androidaps.utils.resources.ResourceHelper
import info.nightscout.androidaps.utils.rx.AapsSchedulers
import info.nightscout.shared.sharedPreferences.SP
import io.reactivex.Completable
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.rxkotlin.plusAssign
import io.reactivex.rxkotlin.subscribeBy
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class TreatmentsCareportalFragment : DaggerFragment() {

    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var sp: SP
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var translator: Translator
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var buildHelper: BuildHelper
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var repository: AppRepository
    @Inject lateinit var uel: UserEntryLogger

    private val disposable = CompositeDisposable()

    private val millsToThePast = T.days(30).msecs()
    private var selectedItems: MutableList<TherapyEvent> = mutableListOf()
    private var showInvalidated = false
    private var toolbar: Toolbar? = null
    private var removeActionMode: ActionMode? = null

    private var _binding: TreatmentsCareportalFragmentBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        TreatmentsCareportalFragmentBinding.inflate(inflater, container, false).also { _binding = it }.root

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar = activity?.findViewById(R.id.toolbar)
        setHasOptionsMenu(true)
        binding.recyclerview.setHasFixedSize(true)
        binding.recyclerview.layoutManager = LinearLayoutManager(view.context)
    }

    private fun refreshFromNightscout() {
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(R.string.careportal), rh.gs(R.string.refresheventsfromnightscout) + " ?", Runnable {
                uel.log(Action.CAREPORTAL_NS_REFRESH, Sources.Treatments)
                disposable += Completable.fromAction { repository.deleteAllTherapyEventsEntries() }
                    .subscribeOn(aapsSchedulers.io)
                    .subscribeBy(
                        onError = { aapsLogger.error("Error removing entries", it) },
                        onComplete = { rxBus.send(EventTherapyEventChange()) }
                    )
                rxBus.send(EventNSClientRestart())
            })
        }
    }

    private fun removeStartedEvents() {
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(R.string.careportal), rh.gs(R.string.careportal_removestartedevents), Runnable {
                uel.log(Action.RESTART_EVENTS_REMOVED, Sources.Treatments)
                disposable += repository.runTransactionForResult(InvalidateAAPSStartedTherapyEventTransaction(rh.gs(R.string.androidaps_start)))
                    .subscribe(
                        { result -> result.invalidated.forEach { aapsLogger.debug(LTag.DATABASE, "Invalidated therapy event $it") } },
                        { aapsLogger.error(LTag.DATABASE, "Error while invalidating therapy event", it) }
                    )
            })
        }
    }

    fun swapAdapter() {
        val now = System.currentTimeMillis()
        disposable +=
            if (showInvalidated)
                repository
                    .getTherapyEventDataIncludingInvalidFromTime(now - millsToThePast, false)
                    .observeOn(aapsSchedulers.main)
                    .subscribe { list -> binding.recyclerview.swapAdapter(RecyclerViewAdapter(list), true) }
            else
                repository
                    .getTherapyEventDataFromTime(now - millsToThePast, false)
                    .observeOn(aapsSchedulers.main)
                    .subscribe { list -> binding.recyclerview.swapAdapter(RecyclerViewAdapter(list), true) }
    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        swapAdapter()
        disposable += rxBus
            .toObservable(EventTherapyEventChange::class.java)
            .observeOn(aapsSchedulers.main)
            .debounce(1L, TimeUnit.SECONDS)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
        disposable += rxBus
            .toObservable(EventTreatmentUpdateGui::class.java) // TODO join with above
            .observeOn(aapsSchedulers.io)
            .debounce(1L, TimeUnit.SECONDS)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        disposable.clear()
    }

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        removeActionMode?.let { it.finish() }
        binding.recyclerview.adapter = null // avoid leaks
        _binding = null
    }

    inner class RecyclerViewAdapter internal constructor(private var therapyList: List<TherapyEvent>) : RecyclerView.Adapter<TherapyEventsViewHolder>() {

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): TherapyEventsViewHolder {
            val v = LayoutInflater.from(viewGroup.context).inflate(R.layout.treatments_careportal_item, viewGroup, false)
            return TherapyEventsViewHolder(v)
        }

        override fun onBindViewHolder(holder: TherapyEventsViewHolder, position: Int) {
            val therapyEvent = therapyList[position]
            holder.binding.ns.visibility = (therapyEvent.interfaceIDs.nightscoutId != null).toVisibility()
            holder.binding.invalid.visibility = therapyEvent.isValid.not().toVisibility()
            val sameDayPrevious = position > 0 && dateUtil.isSameDay(therapyEvent.timestamp, therapyList[position - 1].timestamp)
            holder.binding.date.visibility = sameDayPrevious.not().toVisibility()
            holder.binding.date.text = dateUtil.dateString(therapyEvent.timestamp)
            holder.binding.time.text = dateUtil.timeString(therapyEvent.timestamp)
            holder.binding.duration.text = if (therapyEvent.duration == 0L) "" else dateUtil.niceTimeScalar(therapyEvent.duration, rh)
            holder.binding.note.text = therapyEvent.note
            holder.binding.type.text = translator.translate(therapyEvent.type)
            holder.binding.cbRemove.visibility = (therapyEvent.isValid && removeActionMode != null).toVisibility()
            holder.binding.cbRemove.setOnCheckedChangeListener { _, value ->
                if (value) {
                    selectedItems.add(therapyEvent)
                } else {
                    selectedItems.remove(therapyEvent)
                }
                removeActionMode?.title = rh.gs(R.string.count_selected, selectedItems.size)
            }
            val nextTimestamp = if (therapyList.size != position + 1) therapyList[position + 1].timestamp else 0L
            holder.binding.delimiter.visibility = dateUtil.isSameDay(therapyEvent.timestamp, nextTimestamp).toVisibility()
        }

        override fun getItemCount() = therapyList.size

        inner class TherapyEventsViewHolder(view: View) : RecyclerView.ViewHolder(view) {

            val binding = TreatmentsCareportalItemBinding.bind(view)
        }

    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_treatments_careportal, menu)
        super.onCreateOptionsMenu(menu, inflater)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        menu.findItem(R.id.nav_hide_invalidated)?.isVisible = showInvalidated
        menu.findItem(R.id.nav_show_invalidated)?.isVisible = !showInvalidated
        val nsUploadOnly = !sp.getBoolean(R.string.key_ns_receive_therapy_events, false) || !buildHelper.isEngineeringMode()
        menu.findItem(R.id.nav_refresh_ns)?.isVisible = !nsUploadOnly

        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.nav_remove_items          -> {
                removeActionMode = toolbar?.startActionMode(RemoveActionModeCallback())
                true
            }

            R.id.nav_show_invalidated      -> {
                showInvalidated = true
                rxBus.send(EventTreatmentUpdateGui())
                true
            }

            R.id.nav_hide_invalidated      -> {
                showInvalidated = false
                rxBus.send(EventTreatmentUpdateGui())
                true
            }

            R.id.nav_remove_started_events -> {
                removeStartedEvents()
                true
            }

            R.id.nav_refresh_ns            -> {
                refreshFromNightscout()
                true
            }

            else                           -> false
        }

    inner class RemoveActionModeCallback : ActionMode.Callback {

        override fun onCreateActionMode(mode: ActionMode, menu: Menu?): Boolean {
            mode.menuInflater.inflate(R.menu.menu_delete_selection, menu)
            selectedItems = mutableListOf()
            mode.title = rh.gs(R.string.count_selected, selectedItems.size)
            binding.recyclerview.adapter?.notifyDataSetChanged()
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode?, menu: Menu?) = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.remove_selected -> {
                    removeSelected()
                    mode.finish()
                    true
                }

                else                 -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode?) {
            removeActionMode = null
            binding.recyclerview.adapter?.notifyDataSetChanged()
        }
    }

    private fun getConfirmationText(): String {
        if (selectedItems.size == 1) {
            val therapyEvent = selectedItems.first()
            return rh.gs(R.string.eventtype) + ": " + translator.translate(therapyEvent.type) + "\n" +
                rh.gs(R.string.notes_label) + ": " + (therapyEvent.note ?: "") + "\n" +
                rh.gs(R.string.date) + ": " + dateUtil.dateAndTimeString(therapyEvent.timestamp)
        }
        return rh.gs(R.string.confirm_remove_multiple_items, selectedItems.size)
    }

    private fun removeSelected() {
        if (selectedItems.size > 0)
            activity?.let { activity ->
                OKDialog.showConfirmation(activity, rh.gs(R.string.removerecord), getConfirmationText(), Runnable {
                    selectedItems.forEach { therapyEvent ->
                        uel.log(
                            Action.CAREPORTAL_REMOVED, Sources.Treatments, therapyEvent.note,
                            ValueWithUnit.Timestamp(therapyEvent.timestamp),
                            ValueWithUnit.TherapyEventType(therapyEvent.type)
                        )
                        disposable += repository.runTransactionForResult(InvalidateTherapyEventTransaction(therapyEvent.id))
                            .subscribe(
                                { result -> result.invalidated.forEach { aapsLogger.debug(LTag.DATABASE, "Invalidated therapy event $it") } },
                                { aapsLogger.error(LTag.DATABASE, "Error while invalidating therapy event", it) }
                            )
                    }

                })
            }
    }

}
