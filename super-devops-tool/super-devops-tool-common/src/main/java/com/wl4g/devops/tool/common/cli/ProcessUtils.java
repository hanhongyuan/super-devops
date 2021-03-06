/*
 * Copyright 2017 ~ 2025 the original author or authors. <wanglsir@gmail.com, 983708408@qq.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.wl4g.devops.tool.common.cli;

import static java.util.Arrays.asList;
import static java.util.Objects.isNull;
import static java.util.Objects.nonNull;
import static java.lang.Runtime.*;
import static java.lang.System.currentTimeMillis;
import static org.apache.commons.lang3.StringUtils.EMPTY;
import static org.apache.commons.lang3.SystemUtils.IS_OS_WINDOWS;
import static org.apache.commons.lang3.SystemUtils.JAVA_IO_TMPDIR;
import static org.apache.commons.lang3.SystemUtils.USER_NAME;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;

import com.fasterxml.jackson.annotation.JsonIgnore;

import static com.wl4g.devops.tool.common.io.FileIOUtils.ensureFile;
import static com.wl4g.devops.tool.common.io.FileIOUtils.writeFile;
import static com.wl4g.devops.tool.common.lang.Assert2.*;
import static com.wl4g.devops.tool.common.lang.SystemUtils2.LOCAL_PROCESS_ID;
import static com.wl4g.devops.tool.common.log.SmartLoggerFactory.getLogger;

/**
 * Execution command line process tools.
 * 
 * @author Wangl.sir
 * @version v1.0.0 2019-09-08
 * @since
 */
public abstract class ProcessUtils {
	final protected static Logger log = getLogger(ProcessUtils.class);

	/**
	 * Execution multiple row command-line.
	 * 
	 * @param multiCmd
	 *            multi command string.
	 * @param pwdDir
	 *            execute context directory.
	 * @param stdout
	 *            Standard output file.
	 * @param stderr
	 *            Standard error output file.
	 * @param append
	 *            It takes effect when one of stdout and stderr exists. It is
	 *            used to set whether output mode will be appended
	 * @param redirectToNullIfNecessary
	 *            It takes effect when both stdout and stderr are empty, and is
	 *            used to set whether to redirect standard and exception output
	 *            to the operating system virtual (null) file
	 * 
	 * @return
	 * @throws IOException
	 */
	public final static DelegateProcess execMulti(final String multiCmd, File pwdDir, final File stdout, final File stderr,
			final boolean append, final boolean redirectToNullIfNecessary) throws IOException {
		pwdDir = isNull(pwdDir) ? execScriptTmpDir : pwdDir;
		File tmpScript = new File(pwdDir.getAbsoluteFile(),
				currentTimeMillis() + ".tmpscript" + "." + (IS_OS_WINDOWS ? "bat" : "sh"));
		// Write temporary script.
		writeFile(tmpScript, multiCmd, false);

		// Processing windows permission is not implemented yet!!!
		String callTmpScriptCmd = EMPTY;
		if (!IS_OS_WINDOWS) {
			callTmpScriptCmd = String.format("chmod 700 %s && %s", tmpScript.getAbsolutePath(), tmpScript.getAbsolutePath());
		}
		return execSingle(callTmpScriptCmd, null, stdout, stderr, append, redirectToNullIfNecessary);
	}

	/**
	 * Execution single row command-line.
	 * 
	 * @param singleCmd
	 *            Single row command string.
	 * @param pwdDir
	 *            execute context directory.
	 * @param stdout
	 *            Standard output file.
	 * @param stderr
	 *            Standard error output file.
	 * @param append
	 *            It takes effect when one of stdout and stderr exists. It is
	 *            used to set whether output mode will be appended
	 * @param redirectToNullIfNecessary
	 *            It takes effect when both stdout and stderr are empty, and is
	 *            used to set whether to redirect standard and exception output
	 *            to the operating system virtual (null) file
	 * @return
	 * @throws IOException
	 */
	public final static DelegateProcess execSingle(final String singleCmd, final File pwdDir, final File stdout,
			final File stderr, final boolean append, final boolean redirectToNullIfNecessary) throws IOException {
		Process ps = null;
		String[] cmdarray = buildCrossSingleCommands(singleCmd, stdout, stderr, append, redirectToNullIfNecessary);
		if (nonNull(pwdDir)) {
			state(pwdDir.exists(), String.format("No such directory for pwdDir:[%s]", pwdDir));
			ps = getRuntime().exec(cmdarray, null, pwdDir);
		} else {
			ps = getRuntime().exec(cmdarray);
		}
		return new DelegateProcess(pwdDir, asList(cmdarray), stdout, stderr, ps);
	}

	/**
	 * Build cross platform single row wide fully qualified command line.
	 * 
	 * @param cmd
	 *            Execution command string.
	 * @param append
	 *            Append write?
	 * @return
	 */
	public final static String[] buildCrossSingleCommands(final String cmd, final boolean append) {
		return buildCrossSingleCommands(cmd, null, null, append, true);
	}

	/**
	 * Build cross platform single row wide fully qualified command line.
	 * 
	 * @param cmd
	 *            Execution command string.
	 * @param stdout
	 *            Standard output file.
	 * @param stderr
	 *            Standard error output file.
	 * @param append
	 *            It takes effect when one of stdout and stderr exists. It is
	 *            used to set whether output mode will be appended
	 * @param redirectToNullIfNecessary
	 *            It takes effect when both stdout and stderr are empty, and is
	 *            used to set whether to redirect standard and exception output
	 *            to the operating system virtual (null) file
	 * @return
	 */
	public final static String[] buildCrossSingleCommands(final String cmd, final File stdout, final File stderr,
			final boolean append, boolean redirectToNullIfNecessary) {
		hasText(cmd, "Execute command can't empty.");

		StringBuffer cmdStr = new StringBuffer(cmd);
		String mode = append ? ">>" : ">";
		List<String> cmdarray = new ArrayList<>(8);
		if (IS_OS_WINDOWS) {
			cmdarray.add("C:\\Windows\\System32\\cmd.exe");
			cmdarray.add("/c");

			// Stdout/Stderr
			if (nonNull(stdout)) {
				ensureFile(stdout);
				stdout.getParentFile().mkdirs();

				// e.g: echo "hello" 1>out.log
				cmdStr.append(String.format(" 1%s%s", mode, stdout.getAbsolutePath()));
				redirectToNullIfNecessary = false;
			}
			if (nonNull(stderr)) {
				ensureFile(stderr);
				// e.g: echo "hello" 2>err.log
				cmdStr.append(String.format(" 2%s%s", mode, stderr.getAbsolutePath()));
				redirectToNullIfNecessary = false;
			}
			if (redirectToNullIfNecessary) {
				// e.g: echo "hello" >>/dev/null
				// To use in poweshell: $null
				cmdStr.append(String.format(" %s C:\\nul", mode));
			}
		} else {
			cmdarray.add("/bin/bash");
			cmdarray.add("-c");

			// Stdout/Stderr
			if (nonNull(stdout)) {
				ensureFile(stdout);
				// e.g: echo "hello" 1>out.log
				cmdStr.append(String.format(" 1%s%s", mode, stdout.getAbsolutePath()));
				redirectToNullIfNecessary = false;
			}
			if (nonNull(stderr)) {
				ensureFile(stderr);
				// e.g: echo "hello" 2>err.log
				cmdStr.append(String.format(" 2%s%s", mode, stderr.getAbsolutePath()));
				redirectToNullIfNecessary = false;
			}
			if (redirectToNullIfNecessary) {
				// e.g: echo "hello" >>/dev/null
				cmdStr.append(String.format(" %s /dev/null", mode));
			}
		}
		cmdarray.add(cmdStr.toString());
		return cmdarray.toArray(new String[] {});
	}

	/**
	 * Delegate command process information bean.
	 * 
	 * @author Wangl.sir &lt;Wanglsir@gmail.com, 983708408@qq.com&gt;
	 * @version v1.0.0 2019-10-20
	 * @since
	 */
	public static class DelegateProcess extends Process {

		/** Process commands */
		final private List<String> cmds;

		/** Process context directory */
		final private File pwdDir;

		/** Process commands standard output file */
		final private File stdout;

		/** Process commands standard error output file */
		final private File stderr;

		/** Process object */
		@JsonIgnore
		final transient private Process process;

		public DelegateProcess(File pwdDir, List<String> cmds, File stdout, File stderr, Process process) {
			notEmpty(cmds, "Execution cmdarray must not be empty");
			notNull(process, "Execution process must not be null");
			this.pwdDir = pwdDir;
			this.cmds = cmds;
			this.stdout = stdout;
			this.stderr = stderr;
			this.process = process;
		}

		public File getPwdDir() {
			return pwdDir;
		}

		public List<String> getCmds() {
			return cmds;
		}

		public File getStdout() {
			return stdout;
		}

		public File getStderr() {
			return stderr;
		}

		public Process getProcess() {
			return process;
		}

		@Override
		public OutputStream getOutputStream() {
			return process.getOutputStream();
		}

		@Override
		public InputStream getInputStream() {
			return process.getInputStream();
		}

		@Override
		public InputStream getErrorStream() {
			return process.getErrorStream();
		}

		@Override
		public int waitFor() throws InterruptedException {
			return process.waitFor();
		}

		@Override
		public int exitValue() {
			return process.exitValue();
		}

		@Override
		public void destroy() {
			process.destroy();
		}

	}

	/**
	 * Get or create a Java command line execution temporary parent directory.
	 * 
	 * @param path
	 * @return
	 * @throws IOException
	 */
	private final synchronized static File execScriptTmpDirectory0(String path) {
		File scriptTmpDir = null;
		try {
			scriptTmpDir = new File(JAVA_IO_TMPDIR, path);
			if (!scriptTmpDir.exists()) {
				state(scriptTmpDir.mkdirs(), "Failed to create temp directory [" + scriptTmpDir.getName() + "]");
			}
			return scriptTmpDir;
		} finally {
			if (nonNull(scriptTmpDir)) {
				scriptTmpDir.deleteOnExit();
			}
		}
	}

	/**
	 * Java command line execution temporary directory.
	 */
	final private static File execScriptTmpDir = execScriptTmpDirectory0(
			"java_exec_tmpscript_" + USER_NAME + "/" + LOCAL_PROCESS_ID + "." + System.nanoTime());

}