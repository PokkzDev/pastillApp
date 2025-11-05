package com.example.pastillero

import android.app.AlertDialog
import android.app.TimePickerDialog
import android.os.Bundle
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CalendarView
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.textfield.TextInputEditText
import java.text.SimpleDateFormat
import java.util.*

class CalendarFragment : Fragment() {

    private lateinit var calendarView: CalendarView
    private lateinit var eventsRecyclerView: RecyclerView
    private lateinit var addEventFab: FloatingActionButton
    private lateinit var selectedDateText: TextView
    private lateinit var noEventsText: TextView
    private lateinit var eventAdapter: CalendarEventAdapter
    
    private var selectedDate: Date = Date()
    private val dateFormat = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", Locale("es", "ES"))

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.fragment_calendar, container, false)
        
        initializeViews(view)
        setupCalendar()
        setupRecyclerView()
        setupAddEventButton()
        
        // Set initial date
        updateSelectedDateText()
        loadEventsForSelectedDate()
        
        return view
    }
    
    private fun initializeViews(view: View) {
        calendarView = view.findViewById(R.id.calendarView)
        eventsRecyclerView = view.findViewById(R.id.eventsRecyclerView)
        addEventFab = view.findViewById(R.id.addEventFab)
        selectedDateText = view.findViewById(R.id.selectedDateText)
        noEventsText = view.findViewById(R.id.noEventsText)
    }
    
    private fun setupCalendar() {
        calendarView.setOnDateChangeListener { _, year, month, dayOfMonth ->
            val calendar = Calendar.getInstance()
            calendar.set(year, month, dayOfMonth, 0, 0, 0)
            calendar.set(Calendar.MILLISECOND, 0)
            selectedDate = calendar.time
            
            updateSelectedDateText()
            loadEventsForSelectedDate()
        }
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
    
    private fun updateSelectedDateText() {
        selectedDateText.text = getString(R.string.events_for_date, dateFormat.format(selectedDate))
    }
    
    private fun loadEventsForSelectedDate() {
        val eventsForDate = PillEventManager.getEventsForDate(selectedDate)
        
        if (eventsForDate.isEmpty()) {
            noEventsText.visibility = View.VISIBLE
            eventsRecyclerView.visibility = View.GONE
        } else {
            noEventsText.visibility = View.GONE
            eventsRecyclerView.visibility = View.VISIBLE
        }
        
        eventAdapter.updateEvents(eventsForDate)
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
            
            // Create new event
            val newEvent = PillEvent(
                date = selectedDate,
                pillName = pillName,
                amount = amount,
                time = selectedTime
            )
            
            PillEventManager.addEvent(newEvent)
            loadEventsForSelectedDate()
            
            Toast.makeText(
                requireContext(),
                getString(R.string.event_added_success),
                Toast.LENGTH_SHORT
            ).show()
            
            dialog.dismiss()
        }
        
        dialog.show()
    }
    
    private fun showDeleteConfirmation(event: PillEvent) {
        AlertDialog.Builder(requireContext())
            .setTitle(getString(R.string.delete_event_title))
            .setMessage(getString(R.string.delete_event_message, event.pillName))
            .setPositiveButton(getString(R.string.delete_button)) { _, _ ->
                PillEventManager.removeEvent(event)
                loadEventsForSelectedDate()
                Toast.makeText(
                    requireContext(),
                    getString(R.string.event_deleted_success),
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}