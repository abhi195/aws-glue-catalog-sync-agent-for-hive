package com.amazonaws.services.glue.catalog;

import org.apache.commons.lang3.text.translate.CharSequenceTranslator;
import org.apache.commons.lang3.text.translate.EntityArrays;
import org.apache.commons.lang3.text.translate.LookupTranslator;

public class HiveStringUtils {

    private static final CharSequenceTranslator ESCAPE_HIVE_COMMAND = (new LookupTranslator(new String[][]{{"'", "\\'"}, {";", "\\;"}, {"\\", "\\\\"}})).with(new CharSequenceTranslator[]{new LookupTranslator(EntityArrays.JAVA_CTRL_CHARS_ESCAPE())});

    public static String escapeHiveCommand(String str) {
        return ESCAPE_HIVE_COMMAND.translate(str);
    }
}
