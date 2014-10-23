/*
 * Copyright 2012-2014 the original author or authors.
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

package org.springframework.boot.loader.jar;

import org.springframework.boot.loader.data.RandomAccessData;
import org.springframework.boot.loader.data.RandomAccessData.ResourceAccess;
import org.springframework.boot.loader.util.AsciiBytes;

import java.io.IOException;
import java.io.InputStream;
import java.lang.ref.SoftReference;
import java.util.zip.ZipEntry;

/**
 * Holds the underlying data of a {@link JarEntry}, allowing creation to be deferred until
 * the entry is actually needed.
 *
 * @author Phillip Webb
 */
public final class JarEntryData {

	private static final long LOCAL_FILE_HEADER_SIZE = 30;

	private static final AsciiBytes SLASH = new AsciiBytes("/");

	private final JarFile source;

	private final byte[] header;

	private AsciiBytes name;

	private final byte[] extra;

	private final AsciiBytes comment;

	private final long localHeaderOffset;

	private RandomAccessData data;

	private SoftReference<JarEntry> entry;

	JarFile nestedJar;

	public JarEntryData(JarFile source, byte[] header, InputStream inputStream)
			throws IOException {
		this.source = source;
		this.header = header;
		long nameLength = Bytes.littleEndianValue(header, 28, 2);
		long extraLength = Bytes.littleEndianValue(header, 30, 2);
		long commentLength = Bytes.littleEndianValue(header, 32, 2);
		this.name = new AsciiBytes(Bytes.get(inputStream, nameLength));
		this.extra = Bytes.get(inputStream, extraLength);
		this.comment = new AsciiBytes(Bytes.get(inputStream, commentLength));
		this.localHeaderOffset = Bytes.littleEndianValue(header, 42, 4);
	}

	private JarEntryData(JarEntryData master, JarFile source, AsciiBytes name) {
		this.header = master.header;
		this.extra = master.extra;
		this.comment = master.comment;
		this.localHeaderOffset = master.localHeaderOffset;
		this.source = source;
		this.name = name;
	}

	void setName(AsciiBytes name) {
		this.name = name;
	}

	JarFile getSource() {
		return this.source;
	}

	InputStream getInputStream() throws IOException {
		InputStream inputStream = getData().getInputStream(ResourceAccess.PER_READ);
		if (getMethod() == ZipEntry.DEFLATED) {
			inputStream = new ZipInflaterInputStream(inputStream, getSize());
		}
		return inputStream;
	}

	/**
	 * @return the underlying {@link org.springframework.boot.loader.data.RandomAccessData} for this entry. Generally this
	 * method should not be called directly and instead data should be accessed via
	 * {@link JarFile#getInputStream(java.util.zip.ZipEntry)}.
	 * @throws java.io.IOException
	 */
	public RandomAccessData getData() throws IOException {
		if (this.data == null) {
			// aspectjrt-1.7.4.jar has a different ext bytes length in the
			// local directory to the central directory. We need to re-read
			// here to skip them
			byte[] localHeader = Bytes.get(this.source.getData().getSubsection(
					this.localHeaderOffset, LOCAL_FILE_HEADER_SIZE));
			long nameLength = Bytes.littleEndianValue(localHeader, 26, 2);
			long extraLength = Bytes.littleEndianValue(localHeader, 28, 2);
			this.data = this.source.getData().getSubsection(
					this.localHeaderOffset + LOCAL_FILE_HEADER_SIZE + nameLength
							+ extraLength, getCompressedSize());
		}
		return this.data;
	}

	JarEntry asJarEntry() {
		JarEntry entry = (this.entry == null ? null : this.entry.get());
		if (entry == null) {
			entry = new JarEntry(this);
			entry.setCompressedSize(getCompressedSize());
			entry.setMethod(getMethod());
			entry.setCrc(getCrc());
			entry.setSize(getSize());
			entry.setExtra(getExtra());
			entry.setComment(getComment().toString());
			entry.setSize(getSize());
			entry.setTime(getTime());
			this.entry = new SoftReference<JarEntry>(entry);
		}
		return entry;
	}

	public AsciiBytes getName() {
		return this.name;
	}

	public boolean isDirectory() {
		return this.name.endsWith(SLASH);
	}

	public int getMethod() {
		return (int) Bytes.littleEndianValue(this.header, 10, 2);
	}

	public long getTime() {
		return Bytes.littleEndianValue(this.header, 12, 4);
	}

	public long getCrc() {
		return Bytes.littleEndianValue(this.header, 16, 4);
	}

	public int getCompressedSize() {
		return (int) Bytes.littleEndianValue(this.header, 20, 4);
	}

	public int getSize() {
		return (int) Bytes.littleEndianValue(this.header, 24, 4);
	}

	public byte[] getExtra() {
		return this.extra;
	}

	public AsciiBytes getComment() {
		return this.comment;
	}

	JarEntryData createFilteredCopy(JarFile jarFile, AsciiBytes name) {
		return new JarEntryData(this, jarFile, name);
	}

	/**
	 * Create a new {@link JarEntryData} instance from the specified input stream.
	 * @param source the source {@link JarFile}
	 * @param inputStream the input stream to load data from
	 * @return a {@link JarEntryData} or {@code null}
	 * @throws java.io.IOException
	 */
	static JarEntryData fromInputStream(JarFile source, InputStream inputStream)
			throws IOException {
		byte[] header = new byte[46];
		if (!Bytes.fill(inputStream, header)) {
			return null;
		}
		return new JarEntryData(source, header, inputStream);
	}

}
