package com.example.securepasswordmanager;

import android.text.InputType;
import android.view.View;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class AutofillFormTest {
    @Test
    public void recognizesHtmlPasswordFieldsWithoutAutofillHints() {
        assertTrue(AutofillForm.isPasswordField(null, InputType.TYPE_CLASS_TEXT,
                "password", null));
        assertTrue(AutofillForm.isPasswordField(null, InputType.TYPE_CLASS_TEXT,
                "text", "section-login current-password"));
    }

    @Test
    public void doesNotTreatNewPasswordAsAnExistingLogin() {
        assertFalse(AutofillForm.isPasswordField(null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                "password", "new-password"));
        assertFalse(AutofillForm.isPasswordField(new String[]{"newPassword"},
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                "password", null));
    }

    @Test
    public void recognizesNativePasswordInputTypes() {
        assertTrue(AutofillForm.isPasswordField(null,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                null, null));
    }

    @Test
    public void recognizesHtmlUsernameAndEmailFields() {
        assertTrue(AutofillForm.isUsernameField(null, "section-login username"));
        assertTrue(AutofillForm.isUsernameField(null, "email"));
        assertFalse(AutofillForm.isUsernameField(new String[]{"newUsername"}, "username"));
    }

    @Test
    public void retainsNativeAutofillHints() {
        assertTrue(AutofillForm.isPasswordField(
                new String[]{View.AUTOFILL_HINT_PASSWORD}, 0, null, null));
        assertTrue(AutofillForm.isUsernameField(
                new String[]{View.AUTOFILL_HINT_USERNAME}, null));
    }
}
