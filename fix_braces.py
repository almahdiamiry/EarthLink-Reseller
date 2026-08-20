import os
import glob

for filepath in glob.glob("app/src/test/java/com/example/*.kt"):
    with open(filepath, "r") as f:
        lines = f.readlines()
    
    out_lines = []
    for i, line in enumerate(lines):
        if line.strip() == "}" and i+1 < len(lines) and lines[i+1].strip() == "}":
            # Check if previous lines were getPrepaidNeeded
            if i-1 >= 0 and "getPrepaidNeeded" in lines[i-1]:
                # Found the orphaned brace
                continue
        out_lines.append(line)
        
    with open(filepath, "w") as f:
        f.writelines(out_lines)
