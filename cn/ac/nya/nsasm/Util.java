package cn.ac.nya.nsasm;

import cn.ac.nya.nsasm.NSASM.*;

import java.util.*;

public class Util {

    public static void print(Object value) { System.out.print(value); }

    private static String cleanSymbol(String var, String symbol, String trash) {
        String tmp = var;
        while (tmp.contains(symbol + trash))
            tmp = tmp.replace(symbol + trash, symbol);
        while (tmp.contains(trash + symbol))
            tmp = tmp.replace(trash + symbol, symbol);
        return tmp;
    }

    private static String cleanSymbol(String var, String symbol, String trashA, String trashB) {
        String tmp = var;
        while (tmp.contains(symbol + trashA) || tmp.contains(symbol + trashB))
            tmp = tmp.replace(symbol + trashA, symbol).replace(symbol + trashB, symbol);
        while (tmp.contains(trashA + symbol) || tmp.contains(trashB + symbol))
            tmp = tmp.replace(trashA + symbol, symbol).replace(trashB + symbol, symbol);
        return tmp;
    }

    public static String formatCode(String var) {
        while (var.contains("\r"))
            var = var.replace("\r", "");
        while (var.charAt(0) == '\t' || var.charAt(0) == ' ')
            var = var.substring(1);
        String left, right;
        if (var.contains("\'")) {
            left = var.split("\'")[0];
            right = var.substring(left.length());
        } else if (var.contains("\"")) {
            left = var.split("\"")[0];
            right = var.substring(left.length());
            if (right.substring(1).split("\"").length > 1) {
                if (right.substring(1).split("\"")[1].contains("*")) {
                    right = cleanSymbol(right, "*", "\t", " ");
                }
            }
        } else {
            left = var;
            right = "";
        }
        while (left.contains("\t"))
            left = left.replace("\t", " ");
        while (left.contains("  "))
            left = left.replace("  ", " ");
        left = cleanSymbol(left, ",", " ");
        left = cleanSymbol(left, "=", " ");
        left = cleanSymbol(left, "{", "\t", " ");
        left = cleanSymbol(left, "}", "\t", " ");

        return left + right;
    }

    public static String[][] getSegments(String var) {
        LinkedHashMap<String, String> segBuf = new LinkedHashMap<>();
        String varBuf = ""; Scanner scanner = new Scanner(var);

        while (scanner.hasNextLine()) {
            varBuf = varBuf.concat(formatCode(scanner.nextLine()) + "\n");
        }
        scanner = new Scanner(varBuf);

        String head, body = "", tmp;
        while (scanner.hasNextLine()) {
            head = scanner.nextLine();
            if (!head.contains("{")) continue;
            head = head.replace("{", "");

            tmp = scanner.nextLine();
            while (!tmp.contains("}")) {
                body = body.concat(tmp + "\n");
                tmp = scanner.nextLine();
            }

            segBuf.put(head, body);
        }

        String[][] out = new String[segBuf.size()][2];
        for (int i = 0; i < segBuf.keySet().size(); i++) {
            out[i][0] = (String) segBuf.keySet().toArray()[i];
            out[i][1] = segBuf.get(out[i][0]);
        }

        return out;
    }

    public static String getSegment(String var, String head) {
        String[][] segments = getSegments(var);
        for (String[] i : segments) {
            if (i[0].equals(head))
                return i[1];
        }
        return "";
    }

    public static void run(String path) {

    }

    public static void call(String path) {

    }

    public static void console() {
        print("Now in console mode.\n\n");
        String buf;
        int lines = 1; Result result;

        NSASM nsasm = new NSASM(64,32, 16, null);
        Scanner scanner = new Scanner(System.in);

        while (true) {
            print(lines + " >>> ");
            buf = scanner.nextLine();
            if (buf.length() == 0) {
                lines += 1;
                continue;
            }
            buf = formatCode(buf);
            print("<" + buf + ">\n");
            if (buf.contains("test")) continue;
            result = nsasm.execute(buf);
            if (result == Result.ERR) {
                print("\nNSASM running error!\n");
                print("At line " + lines + ": " + buf + "\n\n");
            } else if (result == Result.ETC) {
                break;
            }
            lines += 1;
        }
    }

}
