package cn.ac.nya.nsasm;

import java.util.*;
import java.util.regex.Pattern;

public class NSASM {

    private enum RegType {
        CHAR, STR, INT, FLOAT
    }

    private class Register {
        RegType type;
        Object data;
        boolean readOnly;

        @Override
        public String toString() {
            return "Type: " + type.name() + "\n" +
                    "Data: " + data.toString() + "\n" +
                    "ReadOnly: " + readOnly;
        }
    }

    private interface Operator {
        public abstract Result run(Register dst, Register src);
    }

    private LinkedHashMap<String, Register> heapManager;
    private LinkedList<Register> stackManager;
    private int stackSize;
    private Register[] regGroup;
    private Register stateReg;
    private String jumpTag;
    private int progCnt;

    private LinkedHashMap<String, Operator> funList;
    private LinkedHashMap<String, String> code;

    public enum Result {
        OK, ERR, ETC
    }

    private enum WordType {
        REG, CHAR, STR, INT,
        FLOAT, VAR, TAG
    }

    private boolean verifyBound(String var, char left, char right) {
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
                return (
                    !var.contains(".") &&
                    var.charAt(var.length() - 1) != 'f' &&
                    var.charAt(var.length() - 1) != 'F'
                ) && (
                    (var.charAt(0) >= '0' && var.charAt(0) <= '9') ||
                    var.charAt(0) == '-' || var.charAt(0) == '+' ||
                    var.charAt(var.length() - 1) == 'h' ||
                    var.charAt(var.length() - 1) == 'H'
                );
            case FLOAT:
                return (
                    var.contains(".") ||
                    var.charAt(var.length() - 1) == 'f' ||
                    var.charAt(var.length() - 1) == 'F'
                ) && (
                    (var.charAt(0) >= '0' && var.charAt(0) <= '9') ||
                    var.charAt(0) == '-' || var.charAt(0) == '+'
                ) && (var.charAt(1) != 'x' || var.charAt(1) != 'X');
            case VAR:
                return !verifyWord(var, WordType.REG) && !verifyWord(var, WordType.CHAR) &&
                       !verifyWord(var, WordType.STR) && !verifyWord(var, WordType.INT) &&
                       !verifyWord(var, WordType.FLOAT) && !verifyWord(var, WordType.TAG);
            case TAG:
                return verifyBound(var, '[', ']');
        }
        return false;
    }

    private Register getRegister(String var) {
        if (var.length() == 0) return null;
        if (verifyWord(var, WordType.REG)) {
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
                    if (var.split("\"").length > 2) {
                        tmp = rep = var.split("\"")[1];
                        Register repeat = getRegister(var.split("\"")[2].replace("*", ""));
                        if (repeat == null) return null;
                        if (repeat.type != RegType.INT) return null;
                        for (int i = 1; i < (int) repeat.data; i++)
                            tmp += rep;
                    } else {
                        tmp = var.split("\"")[1];
                    }
                } catch (Exception e) {
                    return null;
                }
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
            } else if (verifyWord(var, WordType.TAG)) {
                register.type = RegType.STR;
                register.readOnly = true;
                register.data = var;
            } else return null;
            return register;
        }
    }

    public Result execute(String var) {
        String operator, dst, src;
        Register dr = null, sr = null;

        operator = var.split(" ")[0];
        if (operator.length() + 1 < var.length()) {
            if (operator.equals("var")) { //Variable define
                dst = var.substring(operator.length() + 1).split("=")[0];
                if (var.length() <= operator.length() + 1 + dst.length()) return Result.ERR;
                if (var.charAt(operator.length() + 1 + dst.length()) == '=')
                    src = var.substring(operator.length() + 1 + dst.length() + 1);
                else src = "";
                dr = new Register();
                dr.readOnly = true; dr.type = RegType.STR; dr.data = dst;
                sr = getRegister(src);
            } else { //Normal code
                dst = var.substring(operator.length() + 1).split(",")[0];
                if (var.length() <= operator.length() + 1 + dst.length())
                    src = "";
                else if (var.charAt(operator.length() + 1 + dst.length()) == ',')
                    src = var.substring(operator.length() + 1 + dst.length() + 1);
                else src = "";
                dr = getRegister(dst);
                sr = getRegister(src);
            }
        }

        if (!funList.containsKey(operator))
            return verifyWord(operator, WordType.TAG) ? Result.OK : Result.ERR;

        return funList.get(operator).run(dr, sr);
    }

    public void run() {
        if (code == null) return;

    }

    public NSASM(int heapSize, int stackSize, int regCnt, String[][] code) {
        heapManager = new LinkedHashMap<>(heapSize);
        stackManager = new LinkedList<>();
        this.stackSize = stackSize;
        regGroup = new Register[regCnt];
        for (int i = 0; i < regGroup.length; i++) {
            regGroup[i] = new Register();
            regGroup[i].type = RegType.CHAR;
            regGroup[i].readOnly = false;
            regGroup[i].data = 0;
        }
        funList = new LinkedHashMap<>();
        loadFunList();
        this.code = new LinkedHashMap<>();
        if (code == null) return;
        for (String[] seg : code) {
            this.code.put(seg[0], seg[1]);
        }
    }

    protected void loadFunList() {
        funList.put("var", (dst, src) -> {
            if (src == null) return Result.ERR;
            if (!verifyWord((String) dst.data, WordType.VAR)) return Result.ERR;
            if (heapManager.containsKey((String) dst.data)) return Result.ERR;
            if (src.type != RegType.STR) src.readOnly = false;
            heapManager.put((String) dst.data, src);
            return Result.OK;
        });
    }

    public static void main(String[] args) {
        Util.console();
    }

}
