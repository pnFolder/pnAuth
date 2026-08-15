package ru.privatenull.pnauth.message

interface MessageRenderer {
    fun format(): MessageFormat
    fun render(template: String?): String
    fun render(template: String?, replacements: Map<String, String>?): String
}
