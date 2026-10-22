package com.designprototype.workshop

/** The questionnaire feature a test exercises (`shared/questionnaire-features.json`, UNIFY §5.3). One id per annotation; `backend/tests/test_questionnaire_feature_parity.py` reads the marker. */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
annotation class QFeature(val id: String)
