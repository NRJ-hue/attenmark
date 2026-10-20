package com.attendmark.app.data

data class Course(
    val id: String = "",
    val name: String = "",
    val classCode: String = "",
    val facultyEmail: String = "",
    val enrolledStudents: List<String> = emptyList()
)
