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
            Util.print("Usage: nsasm [c/r] [FILE]\n\n");
        } else {
            if (args.length == 2) {
                if (args[0].equals("r")) {
                    Util.run(args[1]);
                    return;
                }
            }
            if (args[0].equals("c")) {
                Util.console();
                return;
            }
            if (args[0].equals("gui")) {
                Util.gui();
                return;
            }
            Util.run(args[0]);
        }
    }

}
