package com.spotify.spawn;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static java.lang.ProcessBuilder.Redirect.INHERIT;
import static java.nio.file.StandardOpenOption.WRITE;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThat;

public class SubprocessesTest {

  public static class Foo {

    public static void main(final String... args) throws IOException {
      System.out.write("Foo!".getBytes("UTF-8"));
      System.out.flush();
    }
  }

  @Test
  public void testSpawn() throws Exception {
    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    final Subprocess subprocess = Subprocesses.process()
        .main(Foo.class)
        .pipeStdout(stdout)
        .spawn();

    subprocess.join();

    final String output = stdout.toString("UTF-8");

    assertThat(output, is("Foo!"));
  }

  public static class Parent {

    public static class Child {

      public static void main(final String... args) throws IOException {
        assertThat(args.length, is(1));
        final String lockfilename = args[0];
        final FileChannel lockfile = FileChannel.open(Paths.get(lockfilename), WRITE);
        System.err.println("locking " + lockfilename);
        System.err.flush();
        lockfile.lock();
        System.err.println("locked " + lockfilename);
        System.err.flush();
        System.out.write(("locked " + lockfilename).getBytes("UTF-8"));
        System.out.flush();
        while (true) {
          try {
            Thread.sleep(1000);
          } catch (InterruptedException ignore) {
          }
        }
      }
    }

    public static void main(final String... args) throws IOException {
      final Subprocess child = Subprocesses.process()
          .main(Child.class)
          .inheritIO()
          .args(args)
          .spawn();
      while (true) {
        try {
          Thread.sleep(1000);
        } catch (InterruptedException ignore) {
        }
      }
    }
  }

  @Test
  public void verifySubprocessTerminatesWhenParentExits() throws Exception {
    final Path lockFilePath = Files.createTempFile("subprocesses", ".lock");
    final FileChannel lockFile = FileChannel.open(lockFilePath, WRITE);

    final ByteArrayOutputStream stdout = new ByteArrayOutputStream();

    final String lockFilename = lockFilePath.toAbsolutePath().toString();
    final Subprocess parent = Subprocesses.process()
        .main(Parent.class)
        .args(lockFilename)
        .redirectStderr(INHERIT)
        .pipeStdout(stdout)
        .spawn();

    // Wait for child to lock file
    while (true) {
      if (stdout.size() == 0) {
        Thread.sleep(100);
        continue;
      }
      final String out = stdout.toString("UTF-8");
      assertThat(out, is("locked " + lockFilename));
      break;
    }

    // Verify that we cannot take the lock while the child is alive
    assertThat(lockFile.tryLock(), is(nullValue()));

    // Kill the parent
    parent.kill();

    // Verify that the child exits by taking the file lock
    while (true) {
      final FileLock lock = lockFile.tryLock();
      if (lock != null) {
        break;
      }
      Thread.sleep(1000);
    }
  }
}