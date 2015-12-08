package com.spotify.spawn;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.spotify.spawn.Subprocesses.startDaemon;

/**
 * A process that terminates if the parent exits.
 */
public class Subprocess {

  private static final Logger log = LoggerFactory.getLogger(Subprocess.class);

  private final AtomicBoolean killed = new AtomicBoolean();

  private final Process process;
  private final String main;
  private final Integer parentExitCode;

  Subprocess(final Process process, final String main, final Integer parentExitCode) {
    this.process = process;
    this.main = main;
    this.parentExitCode = parentExitCode;
    monitor();
  }

  // TODO (dano): communicate the child process pid to the parent via the trampoline using a pipe
//  public int pid() {
//
//  }

  /**
   * Kill the process.
   */
  public void kill() {
    killed.set(true);
    // TODO (dano): send SIGKILL after a timeout if process doesn't exit on SIGTERM.
    process.destroy();
  }

  /**
   * Check if the process was killed by a call to {@link #kill()}.
   */
  public boolean killed() {
    return killed.get();
  }

  /**
   * Block until the process exits.
   */
  public int join() throws InterruptedException {
    return process.waitFor();
  }

  /**
   * Check if the process is still running.
   */
  public boolean running() {
    try {
      process.exitValue();
      return false;
    } catch (IllegalThreadStateException e) {
      return true;
    }
  }

  /**
   * Get stdin of the process.
   */
  public OutputStream stdin() {
    return process.getOutputStream();
  }

  /**
   * Get stdout of the process.
   */
  public InputStream stdout() {
    return process.getInputStream();
  }

  /**
   * Get stderr of the process.
   */
  public InputStream stderr() {
    return process.getErrorStream();
  }

  /**
   * Access the underlying {@link Process}.
   */
  public Process process() {
    return process;
  }

  /**
   * Monitor the subprocess, log when it exits and optionally terminate the parent as well.
   */
  private void monitor() {
    startDaemon(() -> {
      while (true) {
        try {
          final int exitCode = join();
          if (killed()) {
            return;
          }
          if (parentExitCode != null) {
            log.error("{} exited: {}. Exiting.", exitCode);
            System.exit(parentExitCode);
          } else if (exitCode != 0) {
            log.warn("{} exited: {}", main, exitCode);
          } else {
            log.info("{} exited: 0");
          }
          return;
        } catch (InterruptedException ignored) {
        }
      }
    });
  }
}
