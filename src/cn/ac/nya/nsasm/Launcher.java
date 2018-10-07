package cn.ac.nya.nsasm;

/**
 * Created by drzzm on 2017.4.23.
 */
public class Launcher {

    public static void main(String[] args) {
        Util.print("NyaSama Assembly Script Module\n");
        Util.print("Version: ");
        Util.print(NSASM.version);
        Util.print("\n\n");

        if (args.length < 1) {
            Util.print("Usage: nsasm [c/r/i/g] [FILE]\n\n");
        } else {
            if (args.length == 3) {
                if (args[0].equals("c")) {
                    String res = Util.compile(args[1], args[2]);
                    if (res != null)
                        Util.print("Compilation OK.\n\n");
                    return;
                }
            } else if (args.length == 2) {
                if (args[0].equals("r")) {
                    long now = System.nanoTime();
                    Util.run(args[1]);
                    long end = System.nanoTime();
                    double ms = (double) (end - now) / 1e6;
                    Util.print("This script took " +
                        Double.toString(ms) + "ms.\n\n");
                    return;
                } else if (args[0].equals("c")) {
                    String res = Util.compile(args[1], null);
                    Util.print("\n" + res + "\n");
                    return;
                } else  {
                    String[][] segs = Util.getSegments(Util.read(args[0]));
                    NSASM nsasm = new NSASM(64, 32, 32, segs);
                    long now = System.nanoTime();
                    nsasm.call(args[1]);
                    long end = System.nanoTime();
                    double ms = (double) (end - now) / 1e6;
                    Util.print("This script took " +
                            Double.toString(ms) + "ms.\n\n");
                    return;
                }
            }
            if (args[0].equals("i")) {
                Util.interactive();
                return;
            }
            if (args[0].equals("g")) {
                Util.gui();
                return;
            }
            Util.run(args[0]);
        }
    }

}
