package cn.ac.nya.nsasm;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.*;

/**
 * Created by drzzm on 2017.4.21.
 */
public class NSASM {

    public static final String version = "0.61 (Java)";

    public enum RegType {
        CHAR, STR, INT, FLOAT, CODE, MAP, PAR, NUL
    }

    public class Register {
        public RegType type;
        public Object data;
        public int strPtr = 0;
        public boolean readOnly;

        @Override
        public String toString() {
            switch (type) {
                case CODE:
                    return "(\n" + data.toString() + "\n)";
                default:
                    return data.toString();
            }
        }

        @Override
        public boolean equals(Object obj) {
            if (obj instanceof Register)
                return type.equals(((Register) obj).type) && data.equals(((Register) obj).data);
            return false;
        }

        @Override
        public int hashCode() {
            return data.hashCode();
        }

        public void copy(Register reg) {
            type = reg.type;
            data = reg.data;
            strPtr = reg.strPtr;
            readOnly = reg.readOnly;
        }

        public Register() {
            type = RegType.NUL;
            data = 0;
            strPtr = 0;
            readOnly = false;
        }

        public Register(Register reg) {
            copy(reg);
        }
    }

    public class Map extends LinkedHashMap<Register, Register> {
        public Map() { super(); }

        @Override
        public String toString() {
            String str = "M(\n";
            for (Register key : keySet()) {
                if (get(key) == null) continue;
                str = str.concat(key.toString() + "->" + get(key).toString() + "\n");
            }
            str += ")";

            return str;
        }
    }

    public interface Operator {
        Result run(Register dst, Register src, Register ext);
    }

    public interface Param {
        Register mod(Register reg); // if reg is null, it's read, else write
    }

    protected class SafePool<T> extends ArrayList<T> {

        private final ReentrantLock lock = new ReentrantLock();

        public SafePool() { super(); }

        public SafePool(int cap) { super(cap); }

        public int count() {
            try {
                lock.lock();
                return super.size();
            } finally {
                lock.unlock();
            }
        }

        @Override
        public boolean add(T value) {
            try {
                lock.lock();
                return super.add(value);
            } finally {
                lock.unlock();
            }
        }

        public void insert(int index, T value) {
            lock.lock();
            super.add(index, value);
            lock.unlock();
        }

        @Override
        public T get(int index) {
            try {
                lock.lock();
                return super.get(index);
            } finally {
                lock.unlock();
            }
        }

    }

    private LinkedHashMap<String, Register> heapManager;
    private LinkedList<Register> stackManager;
    private int heapSize, stackSize, regCnt;
    protected Register useReg;
    protected Register[] regGroup;
    private Register stateReg;
    private Register prevDstReg;

    private Register argReg;
    public void setArgument(Register reg) {
        argReg = new Register(reg);
    }

    private LinkedList<Integer> backupReg;
    private int progSeg, tmpSeg;
    private int progCnt, tmpCnt;

    protected LinkedHashMap<String, Operator> funcList;
    private LinkedHashMap<String, String[]> code;

    protected LinkedHashMap<String, Param> paramList;

    public enum Result {
        OK, ERR, ETC
    }

    private enum WordType {
        REG, CHAR, STR, INT,
        FLOAT, VAR, TAG, SEG,
        CODE, MAP, PAR
    }

    private boolean verifyBound(String var, char left, char right) {
        if (var.isEmpty()) return false;
        return var.charAt(0) == left && var.charAt(var.length() - 1) == right;
    }

    private boolean verifyWord(String var, WordType type) {
        switch (type) {
            case REG:
                return var.charAt(0) == 'r' || var.charAt(0) == 'R';
            case CHAR:
                return verifyBound(var, '\'', '\'');
            case STR:
                return verifyBound(var, '\"', '\"') ||
                       (var.split("\"").length > 2 && var.contains("*"));
            case INT:
                if (var.endsWith("f") || var.endsWith("F"))
                    return var.startsWith("0x") || var.startsWith("0X");
                return (
                    !var.contains(".")
                ) && (
                    (var.charAt(0) >= '0' && var.charAt(0) <= '9') ||
                    var.charAt(0) == '-' || var.charAt(0) == '+' ||
                    var.endsWith("h") || var.endsWith("H")
                );
            case FLOAT:
                return (
                    var.contains(".") ||
                    var.endsWith("f") || var.endsWith("F")
                ) && (
                    (var.charAt(0) >= '0' && var.charAt(0) <= '9') ||
                    var.charAt(0) == '-' || var.charAt(0) == '+'
                ) && (!var.startsWith("0x") || !var.startsWith("0X"));
            case TAG:
                return verifyBound(var, '[', ']');
            case SEG:
                return verifyBound(var, '<', '>');
            case CODE:
                return verifyBound(var, '(', ')');
            case MAP:
                if (var.charAt(0) == 'm' || var.charAt(0) == 'M')
                    return verifyBound(var.substring(1), '(', ')');
                else return false;
            case PAR:
                return paramList.containsKey(var);
            case VAR:
                return !verifyWord(var, WordType.REG) && !verifyWord(var, WordType.CHAR) &&
                    !verifyWord(var, WordType.STR) && !verifyWord(var, WordType.INT) &&
                    !verifyWord(var, WordType.FLOAT) && !verifyWord(var, WordType.TAG) &&
                    !verifyWord(var, WordType.SEG) && !verifyWord(var, WordType.CODE) &&
                    !verifyWord(var, WordType.MAP) && !verifyWord(var, WordType.PAR);
        }
        return false;
    }

    private Register getRegister(String var) {
        if (var.length() == 0) return null;
        if (verifyWord(var, WordType.PAR)) {
            Register register = new Register();
            register.type = RegType.PAR;
            register.readOnly = true;
            register.data = var;
            return register;
        } else if (verifyWord(var, WordType.REG)) {
            //Register
            int index = Integer.valueOf(var.substring(1));
            if (index < 0 || index >= regGroup.length) return null;
            return regGroup[index];
        } else if (verifyWord(var, WordType.VAR)) {
            //Variable
            if (!heapManager.containsKey(var)) return null;
            return heapManager.get(var);
        } else {
            //Immediate number
            Register register = new Register();
            if (verifyWord(var, WordType.CHAR)) {
                if (var.length() < 3) return null;
                char tmp = 0;
                if (var.charAt(1) == '\\') {
                    if (var.length() < 4) return null;
                    switch (var.charAt(2)) {
                        case '0': tmp = '\0'; break;
                        case 'b': tmp = '\b'; break;
                        case 'n': tmp = '\n'; break;
                        case 'r': tmp = '\r'; break;
                        case 't': tmp = '\t'; break;
                        case '\\': tmp = '\\'; break;
                    }
                } else {
                    tmp = var.charAt(1);
                }
                register.type = RegType.CHAR;
                register.readOnly = true;
                register.data = tmp;
            } else if (verifyWord(var, WordType.STR)) {
                if (var.length() < 3) return null;
                String tmp, rep;
                try {
                    if (var.contains("*")) {
                        tmp = rep = var.split("\"\\*")[0].substring(1);
                        Register repeat = getRegister(var.split("\"\\*")[1]);
                        if (repeat == null) return null;
                        if (repeat.type != RegType.INT) return null;
                        for (int i = 1; i < (int) repeat.data; i++)
                            tmp = tmp.concat(rep);
                    } else {
                        tmp = var.substring(1, var.length() - 1);
                    }
                } catch (Exception e) {
                    return null;
                }

                tmp = Util.formatString(tmp);

                register.type = RegType.STR;
                register.readOnly = true;
                register.data = tmp;
            } else if (verifyWord(var, WordType.INT)) {
                int tmp;
                if (
                    (var.contains("x") || var.contains("X")) ^
                    (var.contains("h") || var.contains("H"))
                ) {
                    if (
                        (var.contains("x") || var.contains("X")) &&
                        (var.contains("h") || var.contains("H"))
                    ) return null;
                    if (
                        (var.charAt(0) < '0' || var.charAt(0) > '9') &&
                        (var.charAt(0) != '+' || var.charAt(0) != '-')
                    ) return null;
                    try {
                        tmp = Integer.valueOf(
                                var.replace("h", "").replace("H", "")
                                   .replace("x", "").replace("X", ""),
                            16);
                    } catch (Exception e) {
                        return null;
                    }
                } else {
                    try {
                        tmp = Integer.parseInt(var);
                    } catch (Exception e) {
                        return null;
                    }
                }
                register.type = RegType.INT;
                register.readOnly = true;
                register.data = tmp;
            } else if (verifyWord(var, WordType.FLOAT)) {
                float tmp;
                try {
                    tmp = Float.parseFloat(var.replace("f", "").replace("F", ""));
                } catch (Exception e) {
                    return null;
                }
                register.type = RegType.FLOAT;
                register.readOnly = true;
                register.data = tmp;
            } else if (verifyWord(var, WordType.TAG) || verifyWord(var, WordType.SEG)) {
                register.type = RegType.STR;
                register.readOnly = true;
                register.data = var;
            } else if (verifyWord(var, WordType.CODE)) {
                register.type = RegType.CODE;
                register.readOnly = true;
                String code = var.substring(1, var.length() - 1);
                code = Util.decodeLambda(code);
                register.data = code;
            } else if (verifyWord(var, WordType.MAP)) {
                String code = var.substring(2, var.length() - 1);

                register = new Register();
                register.type = RegType.MAP;
                register.readOnly = true;
                register.data = new Map();
                code = Util.decodeLambda(code);
                funcList.get("mov").run(regGroup[regCnt], register, null);

                Register reg = new Register();
                reg.type = RegType.CODE; reg.readOnly = true;
                reg.data = code + "\n" + "ret r" + regCnt + "\n";
                register = eval(reg);
            } else return null;
            return register;
        }
    }

    public Result execute(String var) {
        String operator, dst, src, ext;
        Register dr = null, sr = null, er = null;

        operator = var.split(" ")[0];
        operator = operator.toLowerCase(); //To lower case
        if (operator.length() + 1 < var.length()) {
            if (
                operator.equals("var") || operator.equals("int") ||
                operator.equals("char") || operator.equals("float") ||
                operator.equals("str") || operator.equals("code") ||
                operator.equals("map")
            ) { //Variable define
                dst = var.substring(operator.length() + 1).split("=")[0];
                if (var.length() <= operator.length() + 1 + dst.length()) return Result.ERR;
                if (var.charAt(operator.length() + 1 + dst.length()) == '=')
                    src = var.substring(operator.length() + 1 + dst.length() + 1);
                else src = "";
                dr = new Register();
                dr.readOnly = true; dr.type = RegType.STR; dr.data = dst;
                sr = getRegister(src);
            } else if (operator.equals("rem")) {
                //Comment
                return Result.OK;
            } else { //Normal code
                String regs = var.substring(operator.length() + 1);
                String res = ""; Util._string _res = new Util._string();
                LinkedHashMap<String, String> strings = Util.getStrings(regs, _res);
                res = _res.str;
                List<String> args = Util.parseArgs(res, ',');
                for (int i = 0; i < args.size(); i++)
                    for (java.util.Map.Entry<String, String> it : strings.entrySet())
                            args.add(i, args.get(i).replace(it.getKey(), it.getValue()));

                dst = src = ext = "";
                if (args.size() > 0) dst = args.get(0);
                if (args.size() > 1) src = args.get(1);
                if (args.size() > 2) ext = args.get(2);

                dr = getRegister(dst);
                sr = getRegister(src);
                er = getRegister(ext);
            }
        }

        if (!funcList.containsKey(operator))
            return verifyWord(operator, WordType.TAG) ? Result.OK : Result.ERR;

        Register tdr = null, tsr = null, ter = null;
        String pdr = "", psr = "", per = "";
        if (dr != null && dr.type == RegType.PAR) {
            pdr = (String) dr.data;
            tdr = paramList.get(pdr).mod(null);
            dr = new Register(tdr);
        }
        if (sr != null && sr.type == RegType.PAR) {
            psr = (String) sr.data;
            tsr = paramList.get(psr).mod(null);
            sr = new Register(tsr);
        }
        if (er != null && er.type == RegType.PAR) {
            per = (String) er.data;
            ter = paramList.get(per).mod(null);
            er = new Register(ter);
        }

        prevDstReg = dr != null ? dr : prevDstReg;
        Result result = funcList.get(operator).run(dr, sr, er);

        if (ter != null && !ter.equals(er))
            paramList.get(per).mod(er);
        if (tsr != null && !tsr.equals(sr))
            paramList.get(psr).mod(sr);
        if (tdr != null && !tdr.equals(dr))
            paramList.get(pdr).mod(dr);

        return result;
    }

    public Register run() {
        if (code == null) return null;
        Result result; String segBuf, codeBuf;

        progSeg = progCnt = 0;

        for (; progSeg < code.keySet().size(); progSeg++) {
            segBuf = (String) (code.keySet().toArray())[progSeg];
            if (code.get(segBuf) == null) continue;

            for (; progCnt < code.get(segBuf).length; progCnt++) {
                if (tmpSeg >= 0 || tmpCnt >= 0) {
                    progSeg = tmpSeg; progCnt = tmpCnt;
                    tmpSeg = -1; tmpCnt = -1;
                }

                segBuf = (String) (code.keySet().toArray())[progSeg];
                if (code.get(segBuf) == null) break;
                codeBuf = code.get(segBuf)[progCnt];

                if (codeBuf.length() == 0) {
                    continue;
                }

                result = execute(codeBuf);
                if (result == Result.ERR) {
                    Util.print("\nNSASM running error!\n");
                    Util.print("At "+ segBuf + ", line " + (progCnt + 1) + ": " + codeBuf + "\n\n");
                    return null;
                } else if (result == Result.ETC) {
                    if (prevDstReg != null) prevDstReg.readOnly = false;
                    return prevDstReg;
                }
            }

            if (!backupReg.isEmpty()) {
                progCnt = backupReg.pop() + 1;
                progSeg = backupReg.pop() - 1;
            } else progCnt = 0;
        }

        if (prevDstReg != null) prevDstReg.readOnly = false;
        return prevDstReg;
    }

    public void call(String segName) {
        Result result; String segBuf, codeBuf;

        for (int seg = 0; seg < code.keySet().size(); seg++) {
            segBuf = (String) (code.keySet().toArray())[seg];
            if (segName.equals(segBuf)) {
                progSeg = seg;
                progCnt = 0;
                break;
            }
        }

        for (; progSeg < code.keySet().size(); progSeg++) {
            segBuf = (String) (code.keySet().toArray())[progSeg];
            if (code.get(segBuf) == null) continue;

            for (; progCnt < code.get(segBuf).length; progCnt++) {
                if (tmpSeg >= 0 || tmpCnt >= 0) {
                    progSeg = tmpSeg; progCnt = tmpCnt;
                    tmpSeg = -1; tmpCnt = -1;
                }

                segBuf = (String) (code.keySet().toArray())[progSeg];
                if (code.get(segBuf) == null) break;
                codeBuf = code.get(segBuf)[progCnt];

                if (codeBuf.length() == 0) {
                    continue;
                }

                result = execute(codeBuf);
                if (result == Result.ERR) {
                    Util.print("\nNSASM running error!\n");
                    Util.print("At "+ segBuf + ", line " + (progCnt + 1) + ": " + codeBuf + "\n\n");
                    return;
                } else if (result == Result.ETC) {
                    return;
                }
            }

            if (!backupReg.isEmpty()) {
                progCnt = backupReg.pop() + 1;
                progSeg = backupReg.pop() - 1;
            } else progCnt = 0;
        }
    }

    /* TODO: Should override in subclass */
    protected NSASM instance(NSASM base, String[][] code) {
        return new NSASM(base, code);
    }

    protected Register eval(Register register) {
        if (register == null) return null;
        if (register.type != RegType.CODE) return null;
        String[][] code = Util.getSegments(register.data.toString());
        return instance(this, code).run();
    }

    private String[] convToArray(String var) {
        Scanner scanner = new Scanner(var);
        LinkedList<String> buf = new LinkedList<>();

        while (scanner.hasNextLine()) {
            buf.add(scanner.nextLine());
        }

        if (buf.isEmpty()) return null;
        return buf.toArray(new String[0]);
    }

    private Result appendCode(String[][] code) {
        if (code == null) return Result.OK;
        for (String[] seg : code) {
            if (seg[0].startsWith(".")) continue; //This is conf seg
            if (seg[0].startsWith("@")) { //This is override seg
                if (!this.code.containsKey(seg[0].substring(1))) {
                    Util.print("\nNSASM loading error!\n");
                    Util.print("At "+ seg[0].substring(1) + "\n");
                    return Result.ERR;
                }
                this.code.replace(seg[0].substring(1), convToArray(seg[1]));
            } else {
                if (this.code.containsKey(seg[0])) {
                    if (seg[0].startsWith("_pub_")) continue; //This is pub seg
                    Util.print("\nNSASM loading error!\n");
                    Util.print("At "+ seg[0] + "\n");
                    return Result.ERR;
                }
                this.code.put(seg[0], convToArray(seg[1]));
            }
        }
        return Result.OK;
    }

    private void copyRegGroup(NSASM base) {
        for (int i = 0; i < base.regGroup.length; i++)
            this.regGroup[i].copy(base.regGroup[i]);
    }

    private NSASM(NSASM base, String[][] code) {
        this(base.heapSize, base.stackSize, base.regCnt, code);
        copyRegGroup(base);
    }

    public NSASM(int heapSize, int stackSize, int regCnt, String[][] code) {
        heapManager = new LinkedHashMap<>(heapSize);
        stackManager = new LinkedList<>();
        this.heapSize = heapSize;
        this.stackSize = stackSize;
        this.regCnt = regCnt;

        stateReg = new Register();
        stateReg.data = 0;
        stateReg.readOnly = false;
        stateReg.type = RegType.INT;

        backupReg = new LinkedList<>();
        progSeg = 0; progCnt = 0;
        tmpSeg = -1; tmpCnt = -1;

        regGroup = new Register[regCnt + 1];
        for (int i = 0; i < regGroup.length; i++) {
            regGroup[i] = new Register();
            regGroup[i].type = RegType.INT;
            regGroup[i].readOnly = false;
            regGroup[i].data = 0;
        }
        useReg = regGroup[regCnt];
        argReg = null;

        funcList = new LinkedHashMap<>();
        loadFuncList();

        paramList = new LinkedHashMap<>();
        loadParamList();

        this.code = new LinkedHashMap<>();
        if (appendCode(code) == Result.ERR) {
            Util.print("At file: " + "_main_" + "\n\n");
            this.code.clear();
        }
    }
    
    private Object convValue(Object value, RegType type) {
        switch (type) {
            case INT:
                return Integer.valueOf(value.toString());
            case CHAR:
                return (value.toString()).charAt(0);
            case FLOAT:
                return Float.valueOf(value.toString());
        }
        return value;
    }

    private Result calcInt(Register dst, Register src, char type) {
        switch (type) {
            case '+': dst.data = (int) convValue(dst.data, RegType.INT) + (int) convValue(src.data, RegType.INT); break;
            case '-': dst.data = (int) convValue(dst.data, RegType.INT) - (int) convValue(src.data, RegType.INT); break;
            case '*': dst.data = (int) convValue(dst.data, RegType.INT) * (int) convValue(src.data, RegType.INT); break;
            case '/': dst.data = (int) convValue(dst.data, RegType.INT) / (int) convValue(src.data, RegType.INT); break;
            case '%': dst.data = (int) convValue(dst.data, RegType.INT) % (int) convValue(src.data, RegType.INT); break;
            case '&': dst.data = (int) convValue(dst.data, RegType.INT) & (int) convValue(src.data, RegType.INT); break;
            case '|': dst.data = (int) convValue(dst.data, RegType.INT) | (int) convValue(src.data, RegType.INT); break;
            case '~': dst.data = ~(int) convValue(dst.data, RegType.INT); break;
            case '^': dst.data = (int) convValue(dst.data, RegType.INT) ^ (int) convValue(src.data, RegType.INT); break;
            case '<': dst.data = (int) convValue(dst.data, RegType.INT) << (int) convValue(src.data, RegType.INT); break;
            case '>': dst.data = (int) convValue(dst.data, RegType.INT) >> (int) convValue(src.data, RegType.INT); break;
            default: return Result.ERR;
        }
        return Result.OK;
    }

    private Result calcChar(Register dst, Register src, char type) {
        switch (type) {
            case '+': dst.data = (char) convValue(dst.data, RegType.CHAR) + (char) convValue(src.data, RegType.CHAR); break;
            case '-': dst.data = (char) convValue(dst.data, RegType.CHAR) - (char) convValue(src.data, RegType.CHAR); break;
            case '*': dst.data = (char) convValue(dst.data, RegType.CHAR) * (char) convValue(src.data, RegType.CHAR); break;
            case '/': dst.data = (char) convValue(dst.data, RegType.CHAR) / (char) convValue(src.data, RegType.CHAR); break;
            case '%': dst.data = (char) convValue(dst.data, RegType.CHAR) % (char) convValue(src.data, RegType.CHAR); break;
            case '&': dst.data = (char) convValue(dst.data, RegType.CHAR) & (char) convValue(src.data, RegType.CHAR); break;
            case '|': dst.data = (char) convValue(dst.data, RegType.CHAR) | (char) convValue(src.data, RegType.CHAR); break;
            case '~': dst.data = ~(char) convValue(dst.data, RegType.CHAR); break;
            case '^': dst.data = (char) convValue(dst.data, RegType.CHAR) ^ (char) convValue(src.data, RegType.CHAR); break;
            case '<': dst.data = (char) convValue(dst.data, RegType.CHAR) << (char) convValue(src.data, RegType.CHAR); break;
            case '>': dst.data = (char) convValue(dst.data, RegType.CHAR) >> (char) convValue(src.data, RegType.CHAR); break;
            default: return Result.ERR;
        }
        return Result.OK;
    }

    private Result calcFloat(Register dst, Register src, char type) {
        switch (type) {
            case '+': dst.data = (float) convValue(dst.data, RegType.FLOAT) + (float) convValue(src.data, RegType.FLOAT); break;
            case '-': dst.data = (float) convValue(dst.data, RegType.FLOAT) - (float) convValue(src.data, RegType.FLOAT); break;
            case '*': dst.data = (float) convValue(dst.data, RegType.FLOAT) * (float) convValue(src.data, RegType.FLOAT); break;
            case '/': dst.data = (float) convValue(dst.data, RegType.FLOAT) / (float) convValue(src.data, RegType.FLOAT); break;
            default: return Result.ERR;
        }
        return Result.OK;
    }

    private Result calcStr(Register dst, Register src, char type) {
        switch (type) {
            case '+': dst.strPtr = dst.strPtr + (int) convValue(src.data, RegType.INT); break;
            case '-': dst.strPtr = dst.strPtr - (int) convValue(src.data, RegType.INT); break;
            default: return Result.ERR;
        }
        if (dst.strPtr >= dst.data.toString().length()) dst.strPtr = dst.data.toString().length() - 1;
        if (dst.strPtr < 0) dst.strPtr = 0;
        return Result.OK;
    }

    private Result calc(Register dst, Register src, char type) {
        switch (dst.type) {
            case INT:
                return calcInt(dst, src, type);
            case CHAR:
                return calcChar(dst, src, type);
            case FLOAT:
                return calcFloat(dst, src, type);
            case STR:
                return calcStr(dst, src, type);
        }
        return Result.OK;
    }

    protected void loadFuncList() {
        funcList.put("rem", (dst, src, ext) -> {
            return Result.OK;
        });

        funcList.put("var", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.VAR)) return Result.ERR;
            if (heapManager.containsKey((String) dst.data)) return Result.ERR;
            if (src.type != RegType.STR) src.readOnly = false;
            heapManager.put((String) dst.data, src);
            return Result.OK;
        });

        funcList.put("int", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.VAR)) return Result.ERR;
            if (heapManager.containsKey((String) dst.data)) return Result.ERR;
            if (src.type != RegType.INT) return Result.ERR;

            src.readOnly = false;
            heapManager.put((String) dst.data, src);
            return Result.OK;
        });

        funcList.put("char", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.VAR)) return Result.ERR;
            if (heapManager.containsKey((String) dst.data)) return Result.ERR;
            if (src.type != RegType.CHAR) return Result.ERR;

            src.readOnly = false;
            heapManager.put((String) dst.data, src);
            return Result.OK;
        });

        funcList.put("float", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.VAR)) return Result.ERR;
            if (heapManager.containsKey((String) dst.data)) return Result.ERR;
            if (src.type != RegType.FLOAT) return Result.ERR;

            src.readOnly = false;
            heapManager.put((String) dst.data, src);
            return Result.OK;
        });

        funcList.put("str", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.VAR)) return Result.ERR;
            if (heapManager.containsKey((String) dst.data)) return Result.ERR;
            if (src.type != RegType.STR) return Result.ERR;

            src.readOnly = true;
            heapManager.put((String) dst.data, src);
            return Result.OK;
        });

        funcList.put("code", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.VAR)) return Result.ERR;
            if (heapManager.containsKey((String) dst.data)) return Result.ERR;
            if (src.type != RegType.CODE) return Result.ERR;

            src.readOnly = false;
            heapManager.put((String) dst.data, src);
            return Result.OK;
        });

        funcList.put("map", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.VAR)) return Result.ERR;
            if (heapManager.containsKey((String) dst.data)) return Result.ERR;
            if (src.type != RegType.MAP) return Result.ERR;

            src.readOnly = false;
            heapManager.put((String) dst.data, src);
            return Result.OK;
        });

        funcList.put("mov", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (dst.type == RegType.CHAR && src.type == RegType.STR) {
                dst.data = ((String) src.data).charAt(src.strPtr);
            } else if (dst.type == RegType.STR && src.type == RegType.CHAR) {
                char[] array = ((String) dst.data).toCharArray();
                array[dst.strPtr] = (char) src.data;
                dst.data = new String(array);
            } else {
                dst.copy(src);
                if (dst.readOnly) dst.readOnly = false;
            }
            return Result.OK;
        });

        funcList.put("push", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (stackManager.size() >= stackSize) return Result.ERR;
            stackManager.push(new Register(dst));
            return Result.OK;
        });

        funcList.put("pop", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            dst.copy(stackManager.pop());
            return Result.OK;
        });

        funcList.put("in", (dst, src, ext) -> {
            if (src == null) {
                src = new Register();
                src.type = RegType.INT;
                src.data = 0x00;
                src.readOnly = true;
            }
            if (dst == null) return Result.ERR;
			if (src.type != RegType.INT) return Result.ERR;
            String buf; Register reg;
            switch ((int) src.data) {
                case 0x00:
                    if (dst.readOnly && dst.type != RegType.STR) return Result.ERR;
                    buf = Util.scan();
                    switch (dst.type) {
                        case INT:
                            reg = getRegister(buf);
                            if (reg == null) return Result.OK;
                            if (reg.type != RegType.INT) return Result.OK;
                            dst.data = reg.data;
                            break;
                        case CHAR:
                            if (buf.length() < 1) return Result.OK;
                            dst.data = buf.charAt(0);
                            break;
                        case FLOAT:
                            reg = getRegister(buf);
                            if (reg == null) return Result.OK;
                            if (reg.type != RegType.FLOAT) return Result.OK;
                            dst.data = reg.data;
                            break;
                        case STR:
                            if (buf.length() < 1) return Result.OK;
                            dst.data = buf;
                            dst.strPtr = 0;
                            break;
                    }
                    break;
                case 0xFF:
                    Util.print("[DEBUG] <<< ");
                    if (dst.readOnly && dst.type != RegType.STR) return Result.ERR;
                    buf = Util.scan();
                    switch (dst.type) {
                        case INT:
                            reg = getRegister(buf);
                            if (reg == null) return Result.OK;
                            if (reg.type != RegType.INT) return Result.OK;
                            dst.data = reg.data;
                            break;
                        case CHAR:
                            if (buf.length() < 1) return Result.OK;
                            dst.data = buf.charAt(0);
                            break;
                        case FLOAT:
                            reg = getRegister(buf);
                            if (reg == null) return Result.OK;
                            if (reg.type != RegType.FLOAT) return Result.OK;
                            dst.data = reg.data;
                            break;
                        case STR:
                            if (buf.length() < 1) return Result.OK;
                            dst.data = buf;
                            dst.strPtr = 0;
                            break;
                    }
                    break;
                default:
                    return Result.ERR;
            }
            return Result.OK;
        });

        funcList.put("out", (dst, src, ext) -> {
            if (dst == null) return Result.ERR;
            if (src == null) {
                if (dst.type == RegType.STR) {
                    Util.print(((String) dst.data).substring(dst.strPtr));
                } else if (dst.type == RegType.CODE) {
                    Register register = eval(dst);
                    if (register == null) return Result.ERR;
                    Util.print(register.data);
                } else Util.print(dst.data);
            } else {
                if (dst.type != RegType.INT)
                    return Result.ERR;
                switch ((int) dst.data) {
                    case 0x00:
                        if (src.type == RegType.STR) {
                            Util.print(((String) src.data).substring(src.strPtr));
                        } else if (src.type == RegType.CODE) {
                            Register register = eval(src);
                            if (register == null) return Result.ERR;
                            Util.print(register.data);
                        } else Util.print(src.data);
                        break;
                    case 0xFF:
                        Util.print("[DEBUG] >>> ");
                        if (src.type == RegType.STR) {
                            Util.print(((String) src.data).substring(src.strPtr));
                        } else if (src.type == RegType.CODE) {
                            Register register = eval(src);
                            if (register == null) return Result.ERR;
                            Util.print(register.data);
                        } else Util.print(src.data);
                        Util.print('\n');
                        break;
                    default:
                        return Result.ERR;
                }
            }
            return Result.OK;
        });

        funcList.put("prt", (dst, src, ext) -> {
            if (dst == null) return Result.ERR;
            if (src != null) {
                if (dst.type == RegType.STR) {
                    if (dst.readOnly) return Result.ERR;
                    if (src.type == RegType.CHAR && src.data.equals('\b')) {
                        if (dst.data.toString().contains("\n")) {
                            String[] parts = dst.data.toString().split("\n");
                            String res = "";
                            for (int i = 0; i < parts.length - 1; i++) {
                                res = res.concat(parts[i]);
                                if (i < parts.length - 2) res = res.concat("\n");
                            }
                            dst.data = res;
                        }
                    } else if (src.type == RegType.CODE) {
                        Register register = eval(src);
                        if (register == null) return Result.ERR;
                        dst.data = dst.data.toString().concat('\n' + register.data.toString());
                    } else if (src.type == RegType.STR) {
                        dst.data = dst.data.toString().concat('\n' + src.data.toString().substring(src.strPtr));
                    } else return Result.ERR;
                } else if (dst.type == RegType.CODE) {
                    if (dst.readOnly) return Result.ERR;
                    if (src.type == RegType.CHAR && src.data.equals('\b')) {
                        if (dst.data.toString().contains("\n")) {
                            String[] parts = dst.data.toString().split("\n");
                            String res = "";
                            for (int i = 0; i < parts.length - 1; i++) {
                                res = res.concat(parts[i]);
                                if (i < parts.length - 2) res = res.concat("\n");
                            }
                            dst.data = res;
                        }
                    } else if (src.type == RegType.CODE) {
                        dst.data = dst.data.toString().concat('\n' + src.data.toString());
                    } else if (src.type == RegType.STR) {
                        dst.data = dst.data.toString().concat('\n' + src.data.toString().substring(src.strPtr));
                    } else return Result.ERR;
                } else return Result.ERR;
            } else {
                if (dst == null) return Result.ERR;
                if (dst.type == RegType.STR) {
                    Util.print(((String) dst.data).substring(dst.strPtr) + '\n');
                } else if (dst.type == RegType.CODE) {
                    Register register = eval(dst);
                    if (register == null) return Result.ERR;
                    Util.print(register.data.toString() + '\n');
                } else Util.print(dst.data.toString() + '\n');
            }
            return Result.OK;
        });

        funcList.put("add", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("add").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '+');
            else
                return calc(dst, src, '+');
        });

        funcList.put("inc", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            Register register = new Register();
            register.readOnly = false;
            register.type = RegType.CHAR;
            register.data = 1;
            return calc(dst, register, '+');
        });

        funcList.put("sub", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("sub").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '-');
            else
                return calc(dst, src, '-');
        });

        funcList.put("dec", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            Register register = new Register();
            register.readOnly = false;
            register.type = RegType.CHAR;
            register.data = 1;
            return calc(dst, register, '-');
        });

        funcList.put("mul", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mul").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '*');
            else
                return calc(dst, src, '*');
        });

        funcList.put("div", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("div").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '/');
            else
                return calc(dst, src, '/');
        });

        funcList.put("mod", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mod").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '%');
            else
                return calc(dst, src, '%');
        });

        funcList.put("and", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("and").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '&');
            else
                return calc(dst, src, '&');
        });

        funcList.put("or", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("or").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '|');
            else
                return calc(dst, src, '|');
        });

        funcList.put("xor", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("xor").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '^');
            else
                return calc(dst, src, '^');
        });

        funcList.put("not", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            return calc(dst, null, '~');
        });

        funcList.put("shl", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("shl").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '<');
            else
                return calc(dst, src, '<');
        });

        funcList.put("shr", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("shr").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type == RegType.CODE)
                return calc(dst, eval(src), '>');
            else
                return calc(dst, src, '>');
        });

        funcList.put("cmp", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (funcList.get("mov").run(stateReg, dst, null) == Result.ERR)
                return Result.ERR;
            if (src.type == RegType.CODE) {
                if (funcList.get("sub").run(stateReg, eval(src), null) == Result.ERR)
                    return Result.ERR;
            } else {
                if (funcList.get("sub").run(stateReg, src, null) == Result.ERR)
                    return Result.ERR;
			}

            return Result.OK;
        });

        funcList.put("test", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.type == RegType.CODE) {
                if (funcList.get("mov").run(stateReg, eval(dst), null) == Result.ERR)
                    return Result.ERR;
            } else {
                if (funcList.get("mov").run(stateReg, dst, null) == Result.ERR)
                    return Result.ERR;
			}

            Register reg = new Register();
            reg.type = dst.type; reg.readOnly = false; reg.data = 0;
            if (funcList.get("sub").run(stateReg, reg, null) == Result.ERR)
                return Result.ERR;
            return Result.OK;
        });

        funcList.put("jmp", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.type != RegType.STR) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.TAG)) return Result.ERR;
            String tag = (String) dst.data;
            String segBuf, lineBuf;

            for (int seg = 0; seg < code.keySet().size(); seg++) {
                segBuf = (String) (code.keySet().toArray())[seg];
                if (code.get(segBuf) == null) continue;
                for (int line = 0; line < code.get(segBuf).length; line++) {
                    lineBuf = code.get(segBuf)[line];
                    if (tag.equals(lineBuf)) {
                        tmpSeg = seg;
                        tmpCnt = line;
                        return Result.OK;
                    }
                }
            }

            return Result.ERR;
        });

        funcList.put("jz", (dst, src, ext) -> {
            if ((float) convValue(stateReg.data, RegType.FLOAT) == 0) {
                return funcList.get("jmp").run(dst, src, null);
            }
            return Result.OK;
        });

        funcList.put("jnz", (dst, src, ext) -> {
            if ((float) convValue(stateReg.data, RegType.FLOAT) != 0) {
                return funcList.get("jmp").run(dst, src, null);
            }
            return Result.OK;
        });

        funcList.put("jg", (dst, src, ext) -> {
            if ((float) convValue(stateReg.data, RegType.FLOAT) > 0) {
                return funcList.get("jmp").run(dst, src, null);
            }
            return Result.OK;
        });

        funcList.put("jl", (dst, src, ext) -> {
            if ((float) convValue(stateReg.data, RegType.FLOAT) < 0) {
                return funcList.get("jmp").run(dst, src, null);
            }
            return Result.OK;
        });

        funcList.put("loop", (dst, src, ext) -> {
            if (dst == null) return Result.ERR;
            if (src == null) return Result.ERR;
            if (ext == null) return Result.ERR;

            if (dst.type != RegType.INT) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (src.type != RegType.INT) return Result.ERR;
            if (ext.type != RegType.STR) return Result.ERR;
            if (!verifyWord((String) ext.data, WordType.TAG)) return Result.ERR;

            if ((int)src.data > 0) {
                if (funcList.get("inc").run(dst, null, null) == Result.ERR)
                    return Result.ERR;
            } else {
                if (funcList.get("dec").run(dst, null, null) == Result.ERR)
                    return Result.ERR;
            }
            if (funcList.get("cmp").run(dst, src, null) == Result.ERR)
                return Result.ERR;
            if (funcList.get("jnz").run(ext, null, null) == Result.ERR)
                return Result.ERR;

            return Result.OK;
        });

        funcList.put("end", (dst, src, ext) -> {
            if (src == null && dst == null)
                return Result.ETC;
            return Result.ERR;
        });

        funcList.put("ret", (dst, src, ext) -> {
            if (src == null) {
                if (dst != null) prevDstReg = dst;
                else prevDstReg = regGroup[0];
                return Result.ETC;
            }
            return Result.ERR;
        });

        funcList.put("nop", (dst, src, ext) -> {
            if (dst == null && src == null)
                return Result.OK;
            return Result.ERR;
        });

        funcList.put("rst", (dst, src, ext) -> {
            if (dst == null && src == null) {
                tmpSeg = 0;
                tmpCnt = 0;
                return Result.OK;
            }
            return Result.ERR;
        });

        funcList.put("run", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.type != RegType.STR) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.SEG)) return Result.ERR;
            String segBuf, target = (String) dst.data;
            for (int seg = 0; seg < code.keySet().size(); seg++) {
                segBuf = (String) (code.keySet().toArray())[seg];
                if (target.equals(segBuf)) {
                    tmpSeg = seg;
                    tmpCnt = 0;
                    return Result.OK;
                }
            }
            return Result.ERR;
        });

        funcList.put("call", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.type != RegType.STR) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.SEG)) return Result.ERR;
            String segBuf, target = (String) dst.data;
            for (int seg = 0; seg < code.keySet().size(); seg++) {
                segBuf = (String) (code.keySet().toArray())[seg];
                if (target.equals(segBuf)) {
                    tmpSeg = seg;
                    tmpCnt = 0;
                    backupReg.push(progSeg);
                    backupReg.push(progCnt);
                    return Result.OK;
                }
            }
            return Result.OK;
        });

        funcList.put("ld", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.type != RegType.STR && dst.type != RegType.CODE)
                return Result.ERR;

            String path;
            if (dst.type == RegType.CODE) {
                Register res = eval(dst);
                if (res == null) return Result.ERR;
                if (res.type != RegType.STR) return Result.ERR;
                path = res.data.toString();
            } else path = dst.data.toString();

            String code = Util.read(path);
            if (code == null) return Result.ERR;
            String[][] segs = Util.getSegments(code);
            if (appendCode(segs) == Result.ERR) {
                Util.print("At file: " + path + "\n");
                return Result.ERR;
            }

            return Result.OK;
        });

        funcList.put("eval", (dst, src, ext) -> {
            if (dst == null) return Result.ERR;

            if (src == null) eval(dst);
            else {
                if (dst.readOnly) return Result.ERR;
                dst.copy(eval(src));
            }

            return Result.OK;
        });

        funcList.put("par", (dst, src, ext) -> {
            if (dst == null) return Result.ERR;
            if (src == null) return Result.ERR;
            if (ext == null) return Result.ERR;

            if (dst.readOnly) return Result.ERR;
            if (src.type != RegType.CODE) return Result.ERR;
            if (ext.type != RegType.MAP) return Result.ERR;

            if (ext.data instanceof Map) {
                Map map = (Map) ext.data;
                if (!map.isEmpty()) {
                    int cnt = map.size();
                    String[][] code = Util.getSegments(src.data.toString());
                    ArrayList<Register> keys = new ArrayList<>(map.keySet());

                    Thread[] threads = new Thread[cnt];
                    SafePool<Integer> signPool = new SafePool<>();
                    SafePool<NSASM> runnerPool = new SafePool<>();
                    SafePool<Register> outputPool = new SafePool<>();
                    for (int i = 0; i < cnt; i++) {
                        NSASM core = new NSASM(this, code);
                        core.setArgument(map.get(keys.get(i)));
                        runnerPool.add(core);
                        outputPool.add(new Register());
                    }

                    class Runner implements Runnable {
                        private int index;
                        Runner(int index) { this.index = index; }

                        @Override
                        public void run() {
                            NSASM core = runnerPool.get(index);
                            outputPool.insert(index, core.run());
                            signPool.add(index);
                        }
                    }
                    for (int i = 0; i < cnt; i++)
                        threads[i] = new Thread(new Runner(i));

                    for (int i = 0; i < cnt; i++)
                        threads[i].run();
                    while (signPool.count() < cnt)
                        funcList.get("nop").run(null, null, null);

                    dst.type = RegType.MAP;
                    dst.readOnly = false;
                    Map res = new Map();
                    for (int i = 0; i < cnt; i++)
                        res.put(keys.get(i), outputPool.get(i));
                    dst.data = res;
                }
            }

            return Result.OK;
        });

        funcList.put("use", (dst, src, ext) -> {
            if (src != null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (dst.type != RegType.MAP) return Result.ERR;
            useReg = dst;
            return Result.OK;
        });

        funcList.put("put", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("use").run(dst, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("put").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (useReg == null) return Result.ERR;
            if (useReg.type != RegType.MAP) return Result.ERR;
            if (dst.type == RegType.CODE) {
                Register reg = eval(dst);
                if (reg == null) return Result.ERR;
                if (!(reg.data instanceof Map)) return Result.ERR;
                if (((Map)useReg.data).containsKey(reg))
                    ((Map)useReg.data).remove(reg);
                ((Map)useReg.data).put(new Register(reg), new Register(src));
            } else {
                if (((Map)useReg.data).containsKey(dst))
                    ((Map)useReg.data).remove(dst);
                ((Map)useReg.data).put(new Register(dst), new Register(src));
            }

            return Result.OK;
        });

        funcList.put("get", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("use").run(dst, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("get").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            if (useReg == null) return Result.ERR;
            if (useReg.type != RegType.MAP) return Result.ERR;

            if (src.type == RegType.CODE) {
                Register reg = eval(src);
                if (reg == null) return Result.ERR;
                if (!(reg.data instanceof Map)) return Result.ERR;
                if (!((Map)useReg.data).containsKey(reg)) return Result.ERR;
                return funcList.get("mov").run(dst, ((Map)useReg.data).get(reg), null);
            } else {
                if (!((Map)useReg.data).containsKey(src)) return Result.ERR;
                return funcList.get("mov").run(dst, ((Map)useReg.data).get(src), null);
            }
        });

        funcList.put("cat", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("cat").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            switch (dst.type) {
                case STR:
                    if (src.type != RegType.STR)
                        return Result.ERR;
                    dst.data = (String)dst.data + (String)src.data;
                    break;
                case MAP:
                    if (src.type != RegType.MAP)
                        return Result.ERR;
                    if (!(dst.data instanceof Map)) return Result.ERR;
                    if (!(src.data instanceof Map)) return Result.ERR;
                for (java.util.Map.Entry<Register, Register> i : ((Map) src.data).entrySet()) {
                    if (((Map)dst.data).containsKey(i.getKey()))
                        ((Map)dst.data).remove(i.getKey());
                    ((Map)dst.data).put(i.getKey(), i.getValue());
                }
                break;
                default:
                    return Result.ERR;
            }
            return Result.OK;
        });

        funcList.put("dog", (dst, src, ext) -> {
            if (ext != null) {
                if (funcList.get("push").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("dog").run(src, ext, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("mov").run(dst, src, null) == Result.ERR)
                    return Result.ERR;
                if (funcList.get("pop").run(src, null, null) == Result.ERR)
                    return Result.ERR;
                return Result.OK;
            }
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            switch (dst.type) {
                case STR:
                    if (src.type != RegType.STR)
                        return Result.ERR;
                    dst.data = ((String)dst.data).replace((String)src.data, "");
                    break;
                case MAP:
                    if (src.type != RegType.MAP)
                        return Result.ERR;
                    for (java.util.Map.Entry<Register, Register> i : ((Map) src.data).entrySet())
                    if (((Map)dst.data).containsKey(i.getKey()))
                        ((Map)dst.data).remove(i.getKey());
                    break;
                default:
                    return Result.ERR;
            }
            return Result.OK;
        });

        funcList.put("type", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;

            Register reg = new Register();
            reg.type = RegType.STR;
            reg.readOnly = true;
            switch (src.type) {
                case INT: reg.data = "int"; break;
                case CHAR: reg.data = "char"; break;
                case FLOAT: reg.data = "float"; break;
                case STR: reg.data = "str"; break;
                case CODE: reg.data = "code"; break;
                case MAP: reg.data = "map"; break;
                case PAR: reg.data = "par"; break;
                case NUL: reg.data = "nul"; break;
            }
            return funcList.get("mov").run(dst, reg, null);
        });

        funcList.put("len", (dst, src, ext) -> {
            if (dst == null) return Result.ERR;
            if (dst.readOnly) return Result.ERR;
            Register reg = new Register();
            reg.type = RegType.INT;
            reg.readOnly = true;
            if (src == null)
            {
                if (useReg == null) return Result.ERR;
                if (useReg.type != RegType.MAP) return Result.ERR;
                if (!(useReg.data instanceof Map)) return Result.ERR;
                reg.data = ((Map)useReg.data).size();
            }
            else
            {
                if (src.type != RegType.STR) return Result.ERR;
                reg.data = ((String)src.data).length();
            }
            return funcList.get("mov").run(dst, reg, null);
        });

        funcList.put("ctn", (dst, src, ext) -> {
            if (dst == null) return Result.ERR;
            Register reg = new Register();
            reg.type = RegType.INT;
            reg.readOnly = true;
            if (src == null)
            {
                if (useReg == null) return Result.ERR;
                if (useReg.type != RegType.MAP) return Result.ERR;
                if (!(useReg.data instanceof Map)) return Result.ERR;
                reg.data = ((Map)useReg.data).containsKey(dst) ? 1 : 0;
            }
            else
            {
                if (src.type != RegType.STR) return Result.ERR;
                if (dst.type != RegType.STR) return Result.ERR;
                reg.data = ((String)dst.data).contains((String)src.data) ? 1 : 0;
            }
            return funcList.get("mov").run(stateReg, reg, null);
        });

        funcList.put("equ", (dst, src, ext) -> {
            if (src == null) return Result.ERR;
            if (dst == null) return Result.ERR;
            if (src.type != RegType.STR) return Result.ERR;
            if (dst.type != RegType.STR) return Result.ERR;
            Register reg = new Register();
            reg.type = RegType.INT;
            reg.readOnly = true;
            reg.data = ((String)dst.data).equals((String)src.data) ? 0 : 1;
            return funcList.get("mov").run(stateReg, reg, null);
        });
    }

    protected void loadParamList() {
        paramList.put("null", (reg) -> {
            Register res = new Register();
            res.type = RegType.STR;
            res.data = "null";
            return res;
        });
        paramList.put("rand", (reg) -> {
            if (reg == null) {
                Register res = new Register();
                res.type = RegType.FLOAT;
                res.readOnly = true;
                res.data = (float) Math.random();
                return res;
            }
            return reg;
        });
        paramList.put("cinc", (reg) -> {
            if (reg == null) {
                Register res = new Register();
                res.type = RegType.CHAR;
                if (funcList.get("in").run(res, null, null) != Result.OK)
                    return null;
                res.readOnly = true;
                return res;
            }
            return reg;
        });
        paramList.put("cini", (reg) -> {
            if (reg == null) {
                Register res = new Register();
                res.type = RegType.INT;
                if (funcList.get("in").run(res, null, null) != Result.OK)
                    return null;
                res.readOnly = true;
                return res;
            }
            return reg;
        });
        paramList.put("cinf", (reg) -> {
            if (reg == null) {
                Register res = new Register();
                res.type = RegType.FLOAT;
                if (funcList.get("in").run(res, null, null) != Result.OK)
                    return null;
                res.readOnly = true;
                return res;
            }
            return reg;
        });
        paramList.put("cins", (reg) -> {
            if (reg == null) {
                Register res = new Register();
                res.type = RegType.STR;
                if (funcList.get("in").run(res, null, null) != Result.OK)
                    return null;
                res.readOnly = true;
                return res;
            }
            return reg;
        });
        paramList.put("cin", (reg) -> {
            if (reg == null) {
                Register res = new Register();
                res.type = RegType.STR;
                if (funcList.get("in").run(res, null, null) != Result.OK)
                    return null;
                res.readOnly = true;
                return res;
            }
            return reg;
        });
        paramList.put("cout", (reg) -> {
            if (reg == null) return new Register();
            funcList.get("out").run(reg, null, null);
            return reg;
        });
        paramList.put("cprt", (reg) -> {
            if (reg == null) return new Register();
            funcList.get("prt").run(reg, null, null);
            return reg;
        });
        paramList.put("arg", (reg) -> {
            if (reg == null) {
                Register res = new Register();
                if (argReg == null) {
                    res.type = RegType.STR;
                    res.readOnly = true;
                    res.data = "null";
                } else {
                    res.copy(argReg);
                }
                return res;
            }
            return reg;
        });
        paramList.put("tid", (reg) -> {
            if (reg == null) {
                Register res = new Register();
                res.type = RegType.INT;
                res.readOnly = true;
                res.data = (int) Thread.currentThread().getId();
                return res;
            }
            return reg;
        });
    }

}
