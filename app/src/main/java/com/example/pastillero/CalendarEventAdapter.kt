package com.pokkzdev.pastillapp

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

/**
 * Adapter for displaying pill events in a RecyclerView
 */
class CalendarEventAdapter(
    private var events: List<PillEvent>,
    private val onDeleteClick: (PillEvent) -> Unit
) : RecyclerView.Adapter<CalendarEventAdapter.EventViewHolder>() {

    class EventViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val timeBadge: LinearLayout = view.findViewById(R.id.timeBadge)
        val timeText: TextView = view.findViewById(R.id.timeText)
        val pillNameText: TextView = view.findViewById(R.id.pillNameText)
        val amountText: TextView = view.findViewById(R.id.amountText)
        val deleteButton: ImageButton = view.findViewById(R.id.deleteButton)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): EventViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.calendar_event_item, parent, false)
        return EventViewHolder(view)
    }

    override fun onBindViewHolder(holder: EventViewHolder, position: Int) {
        val event = events[position]
        val context = holder.itemView.context
        
        // Set pill name (capitalize first letter)
        holder.pillNameText.text = event.pillName.replaceFirstChar { it.uppercase() }
        
        // Set amount/dosage
        holder.amountText.text = event.amount
        
        // Handle time display
        if (event.time.isNotEmpty()) {
            holder.timeBadge.visibility = View.VISIBLE
            holder.timeText.text = event.time
        } else {
            // Show "Sin hora" when no time is set
            holder.timeBadge.visibility = View.VISIBLE
            holder.timeText.text = context.getString(R.string.no_time_set)
        }
        
        // Delete button
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
