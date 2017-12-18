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
            Util.print("Usage: nsasm [c/r/g] [FILE]\n\n");
        } else {
            if (args.length == 2) {
                if (args[0].equals("r")) {
                    long now = System.nanoTime();
                    Util.run(args[1]);
                    long end = System.nanoTime();
                    double ms = (double) (end - now) / 1e6;
                    Util.print("This script took " +
                        Double.toString(ms) + "ms.\n\n");
                    return;
                }
            }
            if (args[0].equals("c")) {
                Util.console();
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
