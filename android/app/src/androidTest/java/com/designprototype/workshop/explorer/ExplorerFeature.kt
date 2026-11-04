package com.designprototype.workshop.explorer

@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class QFeature(val id: String)
