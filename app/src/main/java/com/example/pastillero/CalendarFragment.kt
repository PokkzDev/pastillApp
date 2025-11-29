package com.pokkzdev.pastillapp

import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class CalendarFragment : Fragment() {

    private lateinit var datePickerButton: LinearLayout
    private lateinit var prevDayButton: ImageButton
    private lateinit var nextDayButton: ImageButton
    private lateinit var todayButton: MaterialButton
    private lateinit var dayOfWeekText: TextView
    private lateinit var dateText: TextView
    private lateinit var eventsRecyclerView: RecyclerView
    private lateinit var addEventFab: FloatingActionButton
    private lateinit var selectedDateText: TextView
    private lateinit var noEventsText: TextView
    private lateinit var eventAdapter: CalendarEventAdapter
    
    private var selectedDate: Date = Date()
    private val dateFormat = SimpleDateFormat("d 'de' MMMM 'de' yyyy", Locale("es", "ES"))
    private val dayOfWeekFormat = SimpleDateFormat("EEEE", Locale("es", "ES"))
    private val dateKeyFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
    private val eventsHeaderFormat = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("es", "ES"))
    
    companion object {
        private const val TAG = "CalendarFragment"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_calendar, container, false)
        
        initializeViews(view)
        setupDatePicker()
        setupNavigationButtons()
        setupRecyclerView()
        setupAddEventButton()
        
        // Set initial date to today
        selectedDate = normalizeToMidnight(Date())
        updateDateDisplay()
        
        return view
    }
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Load events after view is created
        loadEventsForSelectedDate()
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh events when fragment becomes visible
        loadEventsForSelectedDate()
    }
    
    private fun initializeViews(view: View) {
        datePickerButton = view.findViewById(R.id.datePickerButton)
        prevDayButton = view.findViewById(R.id.prevDayButton)
        nextDayButton = view.findViewById(R.id.nextDayButton)
        todayButton = view.findViewById(R.id.todayButton)
        dayOfWeekText = view.findViewById(R.id.dayOfWeekText)
        dateText = view.findViewById(R.id.dateText)
        eventsRecyclerView = view.findViewById(R.id.eventsRecyclerView)
        addEventFab = view.findViewById(R.id.addEventFab)
        selectedDateText = view.findViewById(R.id.selectedDateText)
        noEventsText = view.findViewById(R.id.noEventsText)
    }
    
    private fun setupDatePicker() {
        datePickerButton.setOnClickListener {
            showDatePickerDialog()
        }
    }
    
    private fun setupNavigationButtons() {
        prevDayButton.setOnClickListener {
            navigateDays(-1)
        }
        
        nextDayButton.setOnClickListener {
            navigateDays(1)
        }
        
        todayButton.setOnClickListener {
            goToToday()
        }
    }
    
    private fun showDatePickerDialog() {
        val calendar = Calendar.getInstance()
        calendar.time = selectedDate
        
        val datePickerDialog = DatePickerDialog(
            requireContext(),
            { _, year, month, dayOfMonth ->
                val newCalendar = Calendar.getInstance()
                newCalendar.set(year, month, dayOfMonth, 0, 0, 0)
                newCalendar.set(Calendar.MILLISECOND, 0)
                selectedDate = newCalendar.time
                
                updateDateDisplay()
                loadEventsForSelectedDate()
            },
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.MONTH),
            calendar.get(Calendar.DAY_OF_MONTH)
        )
        
        datePickerDialog.show()
    }
    
    private fun navigateDays(days: Int) {
        val calendar = Calendar.getInstance()
        calendar.time = selectedDate
        calendar.add(Calendar.DAY_OF_MONTH, days)
        selectedDate = calendar.time
        
        updateDateDisplay()
        loadEventsForSelectedDate()
    }
    
    private fun goToToday() {
        selectedDate = normalizeToMidnight(Date())
        updateDateDisplay()
        loadEventsForSelectedDate()
    }
    
    private fun normalizeToMidnight(date: Date): Date {
        val calendar = Calendar.getInstance()
        calendar.time = date
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        return calendar.time
    }
    
    private fun updateDateDisplay() {
        // Update day of week (capitalize first letter)
        val dayOfWeek = dayOfWeekFormat.format(selectedDate)
        dayOfWeekText.text = dayOfWeek.replaceFirstChar { it.uppercase() }
        
        // Update date
        dateText.text = dateFormat.format(selectedDate)
        
        // Update events header
        val headerText = eventsHeaderFormat.format(selectedDate)
        selectedDateText.text = getString(R.string.events_for_date, headerText.replaceFirstChar { it.uppercase() })
        
        // Show/hide today button based on whether we're viewing today
        val today = normalizeToMidnight(Date())
        todayButton.visibility = if (selectedDate == today) View.GONE else View.VISIBLE
    }
    
    private fun setupRecyclerView() {
        eventAdapter = CalendarEventAdapter(emptyList()) { event ->
            showDeleteConfirmation(event)
        }
        
        eventsRecyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = eventAdapter
        }
    }
    
    private fun setupAddEventButton() {
        addEventFab.setOnClickListener {
            showAddEventDialog()
        }
    }
    
    private fun loadEventsForSelectedDate() {
        val userId = FirebaseAuthClient.auth.currentUser?.uid
        if (userId == null) {
            // User not logged in, show empty state
            noEventsText.visibility = View.VISIBLE
            eventsRecyclerView.visibility = View.GONE
            eventAdapter.updateEvents(emptyList())
            return
        }

        Log.d(TAG, "Loading events for date: ${dateKeyFormat.format(selectedDate)}")

        lifecycleScope.launch {
            try {
                val eventsForDate = withContext(Dispatchers.IO) {
                    PillEventFirestoreService.getEventsForDate(userId, selectedDate)
                }
                
                Log.d(TAG, "Loaded ${eventsForDate.size} events from Firestore")
                
                // Update UI on main thread
                withContext(Dispatchers.Main) {
                    if (eventsForDate.isEmpty()) {
                        noEventsText.visibility = View.VISIBLE
                        eventsRecyclerView.visibility = View.GONE
                    } else {
                        noEventsText.visibility = View.GONE
                        eventsRecyclerView.visibility = View.VISIBLE
                    }
                    
                    eventAdapter.updateEvents(eventsForDate)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading events", e)
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.error_loading_events),
                        Toast.LENGTH_SHORT
                    ).show()
                    noEventsText.visibility = View.VISIBLE
                    eventsRecyclerView.visibility = View.GONE
                    eventAdapter.updateEvents(emptyList())
                }
            }
        }
    }
    
    private fun showAddEventDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_add_event, null)
        val pillNameInput = dialogView.findViewById<TextInputEditText>(R.id.pillNameInput)
        val amountInput = dialogView.findViewById<TextInputEditText>(R.id.amountInput)
        val timeInput = dialogView.findViewById<TextInputEditText>(R.id.timeInput)
        
        var selectedTime = ""
        
        // Time picker
        timeInput.setOnClickListener {
            val calendar = Calendar.getInstance()
            val hour = calendar.get(Calendar.HOUR_OF_DAY)
            val minute = calendar.get(Calendar.MINUTE)
            
            TimePickerDialog(requireContext(), { _, selectedHour, selectedMinute ->
                selectedTime = String.format("%02d:%02d", selectedHour, selectedMinute)
                timeInput.setText(selectedTime)
            }, hour, minute, true).show()
        }
        
        val dialog = AlertDialog.Builder(requireContext())
            .setView(dialogView)
            .create()
        
        dialogView.findViewById<Button>(R.id.cancelButton).setOnClickListener {
            dialog.dismiss()
        }
        
        dialogView.findViewById<Button>(R.id.saveButton).setOnClickListener {
            val pillName = pillNameInput.text.toString().trim()
            val amount = amountInput.text.toString().trim()
            
            if (pillName.isEmpty()) {
                pillNameInput.error = getString(R.string.error_empty_pill_name)
                return@setOnClickListener
            }
            
            if (amount.isEmpty()) {
                amountInput.error = getString(R.string.error_empty_amount)
                return@setOnClickListener
            }
            
            val userId = FirebaseAuthClient.auth.currentUser?.uid
            if (userId == null) {
                Toast.makeText(
                    requireContext(),
                    getString(R.string.error_not_logged_in),
                    Toast.LENGTH_SHORT
                ).show()
                return@setOnClickListener
            }
            
            // Disable button to prevent multiple clicks
            val saveButton = dialogView.findViewById<Button>(R.id.saveButton)
            saveButton.isEnabled = false
            
            // Create new event
            val newEvent = PillEvent(
                date = selectedDate,
                pillName = pillName,
                amount = amount,
                time = selectedTime
            )
            
            lifecycleScope.launch {
                try {
                    val success = withContext(Dispatchers.IO) {
                        PillEventFirestoreService.addEvent(userId, newEvent)
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (success) {
                            loadEventsForSelectedDate()
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.event_added_success),
                                Toast.LENGTH_SHORT
                            ).show()
                            dialog.dismiss()
                        } else {
                            saveButton.isEnabled = true
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.error_adding_event),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error adding event", e)
                    withContext(Dispatchers.Main) {
                        saveButton.isEnabled = true
                        Toast.makeText(
                            requireContext(),
                            getString(R.string.error_adding_event),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }
        
        dialog.show()
    }
    
    private fun showDeleteConfirmation(event: PillEvent) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_event_title))
            .setMessage(getString(R.string.delete_event_message, event.pillName))
            .setPositiveButton(getString(R.string.delete_button)) { _, _ ->
                lifecycleScope.launch {
                    try {
                        val success = withContext(Dispatchers.IO) {
                            PillEventFirestoreService.deleteEvent(event.id)
                        }
                        
                        withContext(Dispatchers.Main) {
                            if (success) {
                                loadEventsForSelectedDate()
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.event_deleted_success),
                                    Toast.LENGTH_SHORT
                                ).show()
                            } else {
                                Toast.makeText(
                                    requireContext(),
                                    getString(R.string.error_deleting_event),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error deleting event", e)
                        withContext(Dispatchers.Main) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.error_deleting_event),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
