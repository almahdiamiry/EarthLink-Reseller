import sys

def main():
    if len(sys.argv) < 2:
        print("Usage: python3 mark_checkbox.py <line_num1> <line_num2> ...")
        sys.exit(1)
        
    line_nums = [int(x) for x in sys.argv[1:]]
    filename = "G8 Release-Verification Infrastructure Remediation & Test Matrix Synchronization Implementation Plan.md"
    
    with open(filename, "r", encoding="utf-8") as f:
        lines = f.readlines()
        
    for num in line_nums:
        idx = num - 1
        if 0 <= idx < len(lines):
            line = lines[idx]
            if "- [ ]" in line:
                lines[idx] = line.replace("- [ ]", "- [x]", 1)
                print(f"Marked line {num} as checked.")
            else:
                print(f"Line {num} already checked or doesn't contain '- [ ]'. Line: {line.strip()}")
        else:
            print(f"Line {num} out of bounds.")
            
    with open(filename, "w", encoding="utf-8") as f:
        f.writelines(lines)

if __name__ == "__main__":
    main()
