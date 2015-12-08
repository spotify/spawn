package com.spotify.spawn;

import java.lang.reflect.Method;
import java.util.Arrays;

import jnr.posix.POSIXFactory;

/**
 * Subprocess trampoline. Takes the parent pid and the main class as the first two arguments. The rest of the
 * arguments are passed on to the subprocess main class.
 */
class Trampoline {

  private static final String[] EMPTY_ARGS = {};

  public static void main(String[] args) throws Exception {
    System.out.flush();
    if (args.length < 2) {
      System.err.println("invalid arguments: " + Arrays.toString(args));
      System.exit(Subprocesses.INVALID_ARGUMENTS_EXIT_CODE);
      return;
    }
    try {
      final int ppid = Integer.valueOf(args[0]);
      monitorParent(ppid);
      final String main = args[1];
      final String[] mainArgs = args.length > 2 ? Arrays.copyOfRange(args, 2, args.length) : EMPTY_ARGS;
      final Class<?> mainClass = Class.forName(main);
      final Method mainMethod = Subprocesses.findMain(mainClass);
      if (mainMethod == null) {
        System.err.println("Main method not found: " + main);
        System.exit(Subprocesses.INVALID_ARGUMENTS_EXIT_CODE);
      }
      mainMethod.invoke(null, (Object) mainArgs);
    } catch (Throwable e) {
      e.printStackTrace();
      System.exit(Subprocesses.EXCEPTION_EXIT_CODE);
    }
  }

  private static void monitorParent(final int pid) {
    Subprocesses.startDaemon(() -> {
      while (true) {
        try {
          Thread.sleep(200);
        } catch (InterruptedException e) {
          continue;
        }
        int ppid = POSIXFactory.getPOSIX().getppid();
        if (ppid != pid) {
          // If we've been reparented, then the spawning parent is dead.
          System.exit(Subprocesses.OK_EXIT_CODE);
        }
      }
    });
  }
}
