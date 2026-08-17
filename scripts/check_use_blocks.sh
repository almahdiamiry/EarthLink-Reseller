#!/bin/bash
# A simple CI grep rule to enforce .use {} wrapping for specific IO/Cursor classes.

VIOLATIONS=0

FILES=$(find app/src/main/java -type f \( -name "*.kt" -o -name "*.java" \))

for file in $FILES; do
    awk '
    BEGIN { leak_found = 0; pending_line = 0; pending_text = ""; }
    
    # Skip imports and comments
    /^\s*import / || /^\s*\/\// || /^\s*\*/ { next }
    
    # Match IO class instantiations
    /(FileInputStream|FileOutputStream|ZipInputStream|ZipOutputStream|JsonReader)\s*\(/ {
        if ($0 !~ /\.use/) {
            pending_line = NR
            pending_text = $0
        }
    }
    
    # Match Cursor instantiations via query/rawQuery
    /\.(query|rawQuery)\s*\(/ {
        if ($0 !~ /\.use/ && $0 !~ /@Query/ && $0 !~ /Room/) {
            pending_line = NR
            pending_text = $0
        }
    }
    
    # If we have a pending allocation from the previous line, check if the current line has .use
    pending_line > 0 && NR == pending_line + 1 {
        if ($0 !~ /\.use/ && $0 !~ /close\(\)/) {
            print FILENAME ":" pending_line " - Missing .use {} block for IO/Cursor instantiation:"
            print "  " pending_text
            leak_found = 1
            pending_line = 0
        } else {
            pending_line = 0
        }
    }
    
    END {
        if (pending_line > 0) {
            print FILENAME ":" pending_line " - Missing .use {} block for IO/Cursor instantiation:"
            print "  " pending_text
            leak_found = 1
        }
        if (leak_found) exit 1
    }
    ' "$file" || VIOLATIONS=$((VIOLATIONS + 1))
done

if [ "$VIOLATIONS" -gt 0 ]; then
    echo "ERROR: Found $VIOLATIONS file(s) with IO/Cursor allocations lacking .use {}"
    exit 1
fi

echo "Success: No leaky IO/Cursor allocations found."
exit 0
