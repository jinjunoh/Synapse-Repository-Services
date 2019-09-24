package org.sagebionetworks.repo.model.annotation.v2.annotaitonvalidator;

import org.sagebionetworks.repo.model.annotation.v2.AnnotationsValueType;
import org.sagebionetworks.util.doubles.DoubleUtils;

class DoubleValueValidator implements AnnotationsV2ValueValidator {
	@Override
	public void validate(String key, String value, AnnotationsValueType annotationType) {
		try {
			DoubleUtils.fromString(value);
		} catch (NumberFormatException e){
			throw new IllegalArgumentException("Value associated with key=" + key + " is not valid for type=" + annotationType.name() + ": " + value);
		}
	}
}
