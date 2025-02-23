/*
   Copyright 2019 - 2020 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package nl.nn.adapterframework.xml;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.Logger;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.ext.LexicalHandler;
import org.xml.sax.helpers.DefaultHandler;

import nl.nn.adapterframework.util.LogUtil;
import nl.nn.adapterframework.util.StreamUtil;
import nl.nn.adapterframework.util.XmlUtils;

public class XmlWriter extends DefaultHandler implements LexicalHandler {
	protected Logger log = LogUtil.getLogger(this);

	public static final String DISABLE_OUTPUT_ESCAPING="javax.xml.transform.disable-output-escaping";
	public static final String ENABLE_OUTPUT_ESCAPING="javax.xml.transform.enable-output-escaping";

	private Writer writer;
	private boolean includeXmlDeclaration=false;
	private boolean newlineAfterXmlDeclaration=false;
	private boolean includeComments=true;
	private boolean textMode=false;

	private boolean outputEscaping=true;
	private int elementLevel=0;
	private boolean elementJustStarted;
	private boolean inCdata;
	private List<PrefixMapping> namespaceDefinitions=new ArrayList<>();

	private class PrefixMapping {

		public String prefix;
		public String uri;

		PrefixMapping(String prefix, String uri) {
			this.prefix=prefix;
			this.uri=uri;
		}
	}

	public XmlWriter() {
		writer=new StringWriter();
	}

	public XmlWriter(Writer writer) {
		this.writer=writer;
	}

	public XmlWriter(OutputStream stream) {
		try {
			this.writer=new OutputStreamWriter(stream,StreamUtil.DEFAULT_INPUT_STREAM_ENCODING);
		} catch (UnsupportedEncodingException e) {
			log.error(e);
		}
	}

	@Override
	public void startDocument() throws SAXException {
		try {
			if (includeXmlDeclaration) {
				writer.append("<?xml version=\"1.0\" encoding=\""+StreamUtil.DEFAULT_INPUT_STREAM_ENCODING+"\"?>");
				if (newlineAfterXmlDeclaration) {
					writer.append("\n");
				}
			}
		} catch (IOException e) {
			throw new SaxException(e);
		}
	}


	@Override
	public void endDocument() throws SAXException {
		try {
			writer.flush();
		} catch (IOException e) {
			throw new SaxException(e);
		}
	}

	private void writePrefixMapping(PrefixMapping prefixMapping) throws IOException {
		if (elementLevel==0 && StringUtils.isEmpty(prefixMapping.uri)) {
			return;
		}
		writer.append(" xmlns");
		if (StringUtils.isNotEmpty(prefixMapping.prefix) ) {
			writer.append(":").append(prefixMapping.prefix);
		}
		writer.append("=\"").append(XmlUtils.encodeChars(prefixMapping.uri)).append("\"");
	}

	private void storePrefixMapping(List<PrefixMapping> prefixMappingList, String prefix, String uri) {
		PrefixMapping prefixMapping = new PrefixMapping(prefix,uri);
		prefixMappingList.add(prefixMapping);
	}

	@Override
	public void startPrefixMapping(String prefix, String uri) throws SAXException {
		storePrefixMapping(namespaceDefinitions, prefix, uri);
	}

	@Override
	public void startElement(String uri, String localName, String qName, Attributes attributes) throws SAXException {
		try {
			if (elementJustStarted && !textMode) {
				writer.append(">");
			}
			if (!textMode) {
				writer.append("<"+qName);
				for (int i=0; i<attributes.getLength(); i++) {
					writer.append(" "+attributes.getQName(i)+"=\""+XmlUtils.encodeChars(attributes.getValue(i), true).replace("&#39;", "'")+"\"");
				}
				for (int i=0; i<namespaceDefinitions.size(); i++) {
					writePrefixMapping(namespaceDefinitions.get(i));
				}
			}
			namespaceDefinitions.clear();
			elementJustStarted=true;
			elementLevel++;
		} catch (IOException e) {
			throw new SaxException(e);
		}
	}

	@Override
	public void endElement(String uri, String localName, String qName) throws SAXException {
		try {
			elementLevel--;
			if (!textMode) {
				if (elementJustStarted) {
					elementJustStarted=false;
					writer.append("/>");
				} else {
					writer.append("</"+qName+">");
				}
			}
		} catch (IOException e) {
			throw new SaxException(e);
		}
	}

	@Override
	public void characters(char[] ch, int start, int length) throws SAXException {
		try {
			if (elementJustStarted) {
				elementJustStarted=false;
				if (!textMode) {
					writer.append(">");
				}
			}
			if (textMode) {
				writer.write(ch, start, length);
			} else {
				if (inCdata || !outputEscaping) {
					writer.append(new String(ch, start, length));
				} else {
					writer.append(XmlUtils.encodeCharsAndReplaceNonValidXmlCharacters(new String(ch, start, length)).replace("&quot;", "\"").replace("&#39;", "'"));
				}
			}
		} catch (IOException e) {
			throw new SaxException(e);
		}
	}

	@Override
	public void comment(char[] ch, int start, int length) throws SAXException {
		try {
			if (elementJustStarted && !textMode) {
				writer.append(">");
				elementJustStarted=false;
			}
			if (includeComments) {
				writer.append("<!--").append(new String(ch, start, length)).append("-->");
			}
		} catch (IOException e) {
			throw new SaxException(e);
		}
	}

	@Override
	public void processingInstruction(String target, String data) throws SAXException {
		try {
			if (target.equals(DISABLE_OUTPUT_ESCAPING)) {
				outputEscaping=false;
				return;
			}
			if (target.equals(ENABLE_OUTPUT_ESCAPING)) {
				outputEscaping=true;
				return;
			}
			if (!textMode) {
				writer.append("<?").append(target).append(" ").append(data).append("?>\n");
			}
		} catch (IOException e) {
			throw new SaxException(e);
		}
	}

	@Override
	public void startDTD(String arg0, String arg1, String arg2) throws SAXException {
//		System.out.println("startDTD");
	}

	@Override
	public void endDTD() throws SAXException {
//		System.out.println("endDTD");
	}

	@Override
	public void startCDATA() throws SAXException {
		try {
			if (elementJustStarted) {
				elementJustStarted=false;
				if (!textMode) {
					writer.append(">");
				}
			}
			if (!textMode) {
				writer.append("<![CDATA[");
			}
			inCdata=true;
		} catch (IOException e) {
			throw new SaxException(e);
		}
	}

	@Override
	public void endCDATA() throws SAXException {
		try {
			if (!textMode) {
				writer.append("]]>");
			}
			inCdata=false;
		} catch (IOException e) {
			throw new SaxException(e);
		}
	}

	@Override
	public void startEntity(String arg0) throws SAXException {
//		System.out.println("startEntity ["+arg0+"]");
	}
	@Override
	public void endEntity(String arg0) throws SAXException {
//		System.out.println("endEntity ["+arg0+"]");
	}

	public Writer getWriter() {
		return writer;
	}

	@Override
	public String toString() {
		return writer.toString();
	}

	public void setIncludeXmlDeclaration(boolean includeXmlDeclaration) {
		this.includeXmlDeclaration = includeXmlDeclaration;
	}

	public void setNewlineAfterXmlDeclaration(boolean newlineAfterXmlDeclaration) {
		this.newlineAfterXmlDeclaration = newlineAfterXmlDeclaration;
	}

	public void setIncludeComments(boolean includeComments) {
		this.includeComments = includeComments;
	}

	public void setTextMode(boolean textMode) {
		this.textMode = textMode;
	}

}
