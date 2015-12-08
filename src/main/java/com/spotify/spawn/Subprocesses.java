package com.spotify.spawn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import jnr.posix.POSIXFactory;

import static java.lang.ProcessBuilder.Redirect.PIPE;
import static java.util.Arrays.asList;

/**
 * Utility for spawning subprocesses that terminate when the parent (the spawning process) exits.
 */
public class Subprocesses {

  private static final Logger log = LoggerFactory.getLogger(Subprocesses.class);

  public static final int OK_EXIT_CODE = 0;
  public static final int INVALID_ARGUMENTS_EXIT_CODE = 2;
  public static final int EXCEPTION_EXIT_CODE = 3;
  public static final int SUBPROCESS_EXIT_CODE = 4;

  /**
   * Create a builder for a new {@link Subprocess}.
   */
  public static SubprocessBuilder process() {
    return new SubprocessBuilder();
  }

  public static class SubprocessBuilder {

    private final ProcessBuilder processBuilder = new ProcessBuilder();
    private OutputStream stdoutPipe;
    private OutputStream stderrPipe;
    private InputStream stdinPipe;

    public SubprocessBuilder() {
    }

    private Integer parentExitCodeOnSubprocessExit;
    private List<String> jvmArgs = Collections.emptyList();
    private String main;
    private List<String> args = Collections.emptyList();

    /**
     * Set an exit code that the parent (this) process should exit with if the subprocess exits. {@code null} to not
     * terminate the parent on subprocess exit.
     */
    public SubprocessBuilder terminateParentOnSubprocessExit(final Integer exitCode) {
      this.parentExitCodeOnSubprocessExit = exitCode;
      return this;
    }

    /**
     * Terminate the parent on subprocess exit with exit code {@link Subprocesses#SUBPROCESS_EXIT_CODE}
     */
    public SubprocessBuilder terminateParentOnSubprocessExit() {
      return terminateParentOnSubprocessExit(SUBPROCESS_EXIT_CODE);
    }

    /**
     * Set subprocess work directory.
     */
    public SubprocessBuilder directory(final String directory) {
      return directory(Paths.get(directory));
    }

    /**
     * Set subprocess work directory.
     */
    public SubprocessBuilder directory(final Path directory) {
      return directory(directory.toFile());
    }

    /**
     * Set subprocess work directory.
     */
    public SubprocessBuilder directory(final File directory) {
      processBuilder.directory(directory);
      return this;
    }

    /**
     * Set arguments to be passed the subprocess JVM.
     */
    public SubprocessBuilder jvmArgs(final String... args) {
      return jvmArgs(asList(args));
    }

    /**
     * Set arguments to be passed the subprocess JVM.
     */
    private SubprocessBuilder jvmArgs(final List<String> args) {
      this.jvmArgs = new ArrayList<>(args);
      return this;
    }

    /**
     * Set the main class to be executed by the subprocess JVM.
     */
    public SubprocessBuilder main(final Class<?> main) {
      if (findMain(main) == null) {
        throw new IllegalArgumentException("Main method not found in class: " + main);
      }
      return main(main.getName());
    }

    /**
     * Set the main class to be executed by the subprocess JVM.
     */
    private SubprocessBuilder main(final String main) {
      this.main = main;
      return this;
    }

    /**
     * Set the arguments to be passed to the main method of the subprocess.
     */
    public SubprocessBuilder args(final String... args) {
      return args(asList(args));
    }

    /**
     * Set the arguments to be passed to the main method of the subprocess.
     */
    public SubprocessBuilder args(final List<String> args) {
      this.args = new ArrayList<>(args);
      return this;
    }

    public SubprocessBuilder inheritIO() {
      processBuilder.inheritIO();
      return this;
    }

    public SubprocessBuilder redirectStdout(final ProcessBuilder.Redirect redirect) {
      processBuilder.redirectInput(redirect);
      return this;
    }

    public SubprocessBuilder redirectStderr(final ProcessBuilder.Redirect redirect) {
      processBuilder.redirectError(redirect);
      return this;
    }

    public SubprocessBuilder redirectStdin(final ProcessBuilder.Redirect redirect) {
      processBuilder.redirectOutput(redirect);
      return this;
    }

    /**
     * Pipe stdout from the subprocess to a target {@link OutputStream} on a background thread.
     */
    public SubprocessBuilder pipeStdout(final OutputStream stream) {
      redirectStdout(PIPE);
      this.stdoutPipe = stream;
      return this;
    }

    /**
     * Pipe stderr from the subprocess to a target {@link OutputStream} on a background thread.
     */
    public SubprocessBuilder pipeStderr(final OutputStream stream) {
      redirectStderr(PIPE);
      this.stderrPipe = stream;
      return this;
    }

    /**
     * Pipe an {@link InputStream} into stdin of the subprocess on a background thread.
     */
    public SubprocessBuilder pipeStdin(final InputStream stream) {
      redirectStdin(PIPE);
      this.stdinPipe = stream;
      return this;
    }

    /**
     * Access the underlying {@link ProcessBuilder} to break the glass and configure it directly.
     */
    public ProcessBuilder processBuilder() {
      return processBuilder;
    }

    /**
     * Spawn a new subprocess using these parameters.
     */
    public Subprocess spawn() throws IOException {
      final List<String> cmd = new ArrayList<>();
      cmd.add(System.getProperty("java.home") + "/bin/java");
      cmd.addAll(jvmArgs);
      cmd.add("-cp");
      cmd.add(System.getProperty("java.class.path"));
      cmd.add(Trampoline.class.getName());
      cmd.add(String.valueOf(pid()));
      cmd.add(main);
      cmd.addAll(args);
      processBuilder.command(cmd);
      final Process process = processBuilder.start();
      if (stdoutPipe != null && processBuilder.redirectInput() == PIPE) {
        pipe(process.getInputStream(), stdoutPipe);
      }
      if (stderrPipe != null && processBuilder.redirectError() == PIPE) {
        pipe(process.getErrorStream(), stderrPipe);
      }
      if (stdinPipe != null && processBuilder.redirectOutput() == PIPE) {
        pipe(stdinPipe, process.getOutputStream());
      }
      return new Subprocess(process, main, parentExitCodeOnSubprocessExit);
    }
  }

  static Method findMain(final Class<?> cls) {
    for (final Method method : cls.getMethods()) {
      int mod = method.getModifiers();
      if (method.getName().equals("main") &&
          Modifier.isPublic(mod) && Modifier.isStatic(mod) &&
          method.getParameterTypes().length == 1 &&
          method.getParameterTypes()[0].equals(String[].class)) {
        return method;
      }
    }
    return null;
  }

  static int pid() {
    return POSIXFactory.getPOSIX().getpid();
  }

  static void pipe(final InputStream in, final OutputStream out) {
    final byte[] buf = new byte[4096];
    startDaemon(() -> {
      try {
        final int n = in.read(buf);
        if (n == -1) {
          return;
        }
        out.write(buf, 0, n);
      } catch (IOException e) {
        log.warn("pipe error", e);
      }
    });
  }

  static void startDaemon(final Runnable task) {
    final Thread thread = new Thread(task);
    thread.setDaemon(true);
    thread.start();
  }

  private Subprocesses() {
    throw new UnsupportedOperationException();
  }
}