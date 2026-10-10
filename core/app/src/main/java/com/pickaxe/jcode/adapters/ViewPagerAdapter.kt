package com.pickaxe.jcode.adapters

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.pickaxe.jcode.artificial.agents.AIAgentManager
import com.pickaxe.jcode.fragments.ChatFragment
import com.pickaxe.jcode.fragments.AIHistoryFragment

class ViewPagerAdapter(
    fragmentActivity: FragmentActivity,
    private val aiAgent: AIAgentManager
) : FragmentStateAdapter(fragmentActivity) {

    override fun getItemCount(): Int = 2

    override fun createFragment(position: Int): Fragment {
        return when (position) {
            0 -> ChatFragment.newInstance(aiAgent)
            1 -> AIHistoryFragment.newInstance(aiAgent)
            else -> throw IllegalArgumentException("Invalid position: $position")
        }
    }
}