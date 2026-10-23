package com.cretorix.securepasswordmanager;

import android.app.assist.AssistStructure;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Build;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.service.autofill.FillRequest;
import android.service.autofill.FillContext;
import android.text.InputType;
import android.util.Pair;

import java.util.ArrayList;
import java.util.List;

final class AutofillForm {
    private static final int MAX_STRUCTURE_DEPTH = 100;
    private static final int MAX_STRUCTURE_NODES = 10_000;
    static final String EXTRA_PASSWORD_ID =
            "com.cretorix.securepasswordmanager.autofill.PASSWORD_ID";
    static final String EXTRA_USERNAME_ID =
            "com.cretorix.securepasswordmanager.autofill.USERNAME_ID";
    static final String EXTRA_WEB_DOMAIN =
            "com.cretorix.securepasswordmanager.autofill.WEB_DOMAIN";
    static final String EXTRA_TARGET_PACKAGE =
            "com.cretorix.securepasswordmanager.autofill.TARGET_PACKAGE";
    static final String EXTRA_REQUIRE_PICKER =
            "com.cretorix.securepasswordmanager.autofill.REQUIRE_PICKER";

    final AutofillId passwordId;
    final AutofillId usernameId;
    final String webDomain;
    final String targetPackage;
    final boolean requirePicker;

    private AutofillForm(AutofillId passwordId, AutofillId usernameId, String webDomain,
                        String targetPackage, boolean requirePicker) {
        this.passwordId = passwordId;
        this.usernameId = usernameId;
        this.webDomain = webDomain;
        this.targetPackage = targetPackage;
        this.requirePicker = requirePicker;
    }

    static AutofillForm from(FillRequest request) {
        List<FillContext> contexts = request.getFillContexts();
        if (contexts.isEmpty()) {
            return null;
        }
        FillContext context = contexts.get(contexts.size() - 1);
        AssistStructure structure = context.getStructure();
        List<Field> fields = new ArrayList<>();
        int[] remainingNodes = {MAX_STRUCTURE_NODES};
        for (int window = 0; window < structure.getWindowNodeCount(); window++) {
            if (remainingNodes[0] == 0) {
                break;
            }
            AssistStructure.WindowNode windowNode = structure.getWindowNodeAt(window);
            collect(windowNode.getRootViewNode(), null, new ArrayList<>(), fields,
                    0, remainingNodes);
        }

        Field password = null;
        AutofillId focusedId = context.getFocusedId();
        for (Field field : fields) {
            if (field.password && field.id.equals(focusedId)) {
                password = field;
                break;
            }
        }
        if (password == null) {
            for (Field field : fields) {
                if (field.password) {
                    password = field;
                    break;
                }
            }
        }
        if (password == null) {
            return null;
        }

        Field username = null;
        int bestCommonPath = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (Field field : fields) {
            if (!field.username || field.id.equals(password.id)
                    || !compatibleDomains(password.webDomain, field.webDomain)) {
                continue;
            }
            int commonPath = commonPrefixLength(password.path, field.path);
            int distance = Math.abs(password.order - field.order);
            if (commonPath > bestCommonPath
                    || (commonPath == bestCommonPath && distance < bestDistance)) {
                username = field;
                bestCommonPath = commonPath;
                bestDistance = distance;
            }
        }
        ComponentName activity = structure.getActivityComponent();
        return new AutofillForm(password.id, username == null ? null : username.id,
                password.webDomain, activity == null ? null : activity.getPackageName(), false);
    }

    void addToIntent(Intent intent) {
        intent.putExtra(EXTRA_PASSWORD_ID, passwordId);
        if (usernameId != null) {
            intent.putExtra(EXTRA_USERNAME_ID, usernameId);
        }
        if (webDomain != null) {
            intent.putExtra(EXTRA_WEB_DOMAIN, webDomain);
        }
        if (targetPackage != null) {
            intent.putExtra(EXTRA_TARGET_PACKAGE, targetPackage);
        }
        intent.putExtra(EXTRA_REQUIRE_PICKER, requirePicker);
    }

    static AutofillForm fromIntent(Intent intent) {
        AutofillId passwordId = getAutofillId(intent, EXTRA_PASSWORD_ID);
        AutofillId usernameId = getAutofillId(intent, EXTRA_USERNAME_ID);
        String webDomain = intent.getStringExtra(EXTRA_WEB_DOMAIN);
        String targetPackage = intent.getStringExtra(EXTRA_TARGET_PACKAGE);
        boolean requirePicker = intent.getBooleanExtra(EXTRA_REQUIRE_PICKER, false);
        return passwordId == null ? null
                : new AutofillForm(passwordId, usernameId, webDomain,
                targetPackage, requirePicker);
    }

    AutofillForm requiringPicker() {
        return new AutofillForm(passwordId, usernameId, webDomain, targetPackage, true);
    }

    private static AutofillId getAutofillId(Intent intent, String key) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return intent.getParcelableExtra(key, AutofillId.class);
        }
        return intent.getParcelableExtra(key);
    }

    private static void collect(AssistStructure.ViewNode node, String inheritedDomain,
                                List<Integer> path, List<Field> fields, int depth,
                                int[] remainingNodes) {
        if (node == null || depth > MAX_STRUCTURE_DEPTH || remainingNodes[0] == 0) {
            return;
        }
        remainingNodes[0]--;
        String webDomain = node.getWebDomain();
        if (webDomain == null || webDomain.trim().isEmpty()) {
            webDomain = inheritedDomain;
        }
        AutofillId id = node.getAutofillId();
        if (id != null) {
            String[] hints = node.getAutofillHints();
            ViewStructure.HtmlInfo htmlInfo = node.getHtmlInfo();
            String htmlType = htmlAttribute(htmlInfo, "type");
            String autocomplete = htmlAttribute(htmlInfo, "autocomplete");
            boolean password = isPasswordField(hints, node.getInputType(),
                    htmlType, autocomplete);
            boolean username = isUsernameField(hints, autocomplete);
            if (password || username) {
                fields.add(new Field(id, webDomain, password, username,
                        new ArrayList<>(path), fields.size()));
            }
        }
        for (int index = 0; index < node.getChildCount(); index++) {
            path.add(index);
            collect(node.getChildAt(index), webDomain, path, fields,
                    depth + 1, remainingNodes);
            path.remove(path.size() - 1);
        }
    }

    private static boolean hasHint(String[] hints, String expected) {
        if (hints == null) {
            return false;
        }
        for (String hint : hints) {
            if (expected.equals(hint)) {
                return true;
            }
        }
        return false;
    }

    static boolean isPasswordField(String[] hints, int inputType, String htmlType,
                                   String autocomplete) {
        if (hasHint(hints, "newPassword")
                || hasAutocompleteToken(autocomplete, "new-password")) {
            return false;
        }
        return hasHint(hints, View.AUTOFILL_HINT_PASSWORD)
                || isPasswordInputType(inputType)
                || "password".equalsIgnoreCase(htmlType)
                || hasAutocompleteToken(autocomplete, "current-password");
    }

    static boolean isUsernameField(String[] hints, String autocomplete) {
        return !hasHint(hints, "newUsername")
                && !hasAutocompleteToken(autocomplete, "new-username")
                && (hasHint(hints, View.AUTOFILL_HINT_USERNAME)
                || hasHint(hints, View.AUTOFILL_HINT_EMAIL_ADDRESS)
                || hasAutocompleteToken(autocomplete, "username")
                || hasAutocompleteToken(autocomplete, "email"));
    }

    private static boolean isPasswordInputType(int inputType) {
        int inputClass = inputType & InputType.TYPE_MASK_CLASS;
        int variation = inputType & InputType.TYPE_MASK_VARIATION;
        if (inputClass == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
        }
        return inputClass == InputType.TYPE_CLASS_NUMBER
                && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private static String htmlAttribute(ViewStructure.HtmlInfo htmlInfo, String name) {
        if (htmlInfo == null) {
            return null;
        }
        for (Pair<String, String> attribute : htmlInfo.getAttributes()) {
            if (attribute.first != null && name.equalsIgnoreCase(attribute.first)) {
                return attribute.second;
            }
        }
        return null;
    }

    private static boolean hasAutocompleteToken(String autocomplete, String expected) {
        if (autocomplete == null) {
            return false;
        }
        for (String token : autocomplete.trim().split("\\s+")) {
            if (expected.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    private static boolean compatibleDomains(String left, String right) {
        return left == null || right == null || left.equalsIgnoreCase(right);
    }

    private static int commonPrefixLength(List<Integer> left, List<Integer> right) {
        int length = Math.min(left.size(), right.size());
        int common = 0;
        while (common < length && left.get(common).equals(right.get(common))) {
            common++;
        }
        return common;
    }

    private static final class Field {
        final AutofillId id;
        final String webDomain;
        final boolean password;
        final boolean username;
        final List<Integer> path;
        final int order;

        Field(AutofillId id, String webDomain, boolean password, boolean username,
              List<Integer> path, int order) {
            this.id = id;
            this.webDomain = webDomain;
            this.password = password;
            this.username = username;
            this.path = path;
            this.order = order;
        }
    }
}
