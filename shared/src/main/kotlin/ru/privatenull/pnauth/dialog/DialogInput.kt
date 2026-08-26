package ru.privatenull.pnauth.dialog

import net.kyori.adventure.text.Component

/** A platform-neutral input displayed inside a player dialog. */
sealed interface DialogInput {
    fun id(): String
    fun label(): Component

    @JvmRecord
    data class Text(
        private val _id: String,
        private val _label: Component,
        val labelVisible: Boolean,
        val initialValue: String,
        val maximumLength: Int,
        val width: Int,
        val multiline: Multiline?
    ) : DialogInput {
        override fun id(): String = _id
        override fun label(): Component = _label

        @JvmRecord
        data class Multiline(val maximumLines: Int?, val height: Int?)
    }

    @JvmRecord
    data class Toggle(
        private val _id: String,
        private val _label: Component,
        val initialValue: Boolean,
        val onTrue: String?,
        val onFalse: String?
    ) : DialogInput {
        override fun id(): String = _id
        override fun label(): Component = _label
    }

    @JvmRecord
    data class Choice(
        private val _id: String,
        private val _label: Component,
        val labelVisible: Boolean,
        val width: Int,
        val options: List<Option>
    ) : DialogInput {
        override fun id(): String = _id
        override fun label(): Component = _label

        @JvmRecord
        data class Option(val id: String, val display: Component, val initial: Boolean)
    }

    /** Numeric slider matching Minecraft's number-range input. */
    @JvmRecord
    data class NumberRange(
        private val _id: String,
        private val _label: Component,
        val labelFormat: String?,
        val width: Int,
        val start: Float,
        val end: Float,
        val initial: Float?,
        val step: Float?
    ) : DialogInput {
        override fun id(): String = _id
        override fun label(): Component = _label
    }
}
