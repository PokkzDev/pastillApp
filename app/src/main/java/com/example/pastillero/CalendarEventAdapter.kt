package com.example.pastillero

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Adapter for displaying pill events in a RecyclerView
 */
class CalendarEventAdapter(
    private var events: List<PillEvent>,
    private val onDeleteClick: (PillEvent) -> Unit
) : RecyclerView.Adapter<CalendarEventAdapter.EventViewHolder>() {

    private val timeFormat = SimpleDateFormat("HH:mm", Locale.getDefault())

    class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val pillNameText: TextView = view.findViewById(R.id.pillNameText)
        val amountText: TextView = view.findViewById(R.id.amountText)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.calendar_event_item, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val event = events[position]
        holder.pillNameText.text = event.pillName
        holder.amountText.text = event.amount
        
        if (event.time.isNotEmpty()) {
            holder.timeText.text = event.time
            holder.timeText.visibility = View.VISIBLE
        } else {
            holder.timeText.visibility = View.GONE
        }
        
        holder.deleteButton.setOnClickListener {
            onDeleteClick(event)
        }
    }

    override fun getItemCount(): Int = events.size

    fun updateEvents(newEvents: List<PillEvent>) {
        events = newEvents
        notifyDataSetChanged()
    }
}
