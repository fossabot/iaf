/*
   Copyright 2016, 2020 Nationale-Nederlanden, 2020 WeAreFrank!

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
package nl.nn.adapterframework.pipes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import nl.nn.adapterframework.configuration.ConfigurationException;
import nl.nn.adapterframework.core.Adapter;
import nl.nn.adapterframework.core.IPipeLineSession;
import nl.nn.adapterframework.core.PipeRunException;
import nl.nn.adapterframework.core.PipeRunResult;
import nl.nn.adapterframework.doc.IbisDoc;
import nl.nn.adapterframework.http.RestListenerUtils;
import nl.nn.adapterframework.soap.Wsdl;
import nl.nn.adapterframework.stream.Message;
import nl.nn.adapterframework.util.AppConstants;
import nl.nn.adapterframework.util.StreamUtil;

/**
 * Generate WSDL of parent or specified adapter.
 *

 * @author Jaco de Groot
 */
public class WsdlGeneratorPipe extends FixedForwardPipe {
	private String from = "parent";
	
	private String dtapStage;
	private String dtapSide;

	@Override
	public void configure() throws ConfigurationException {
		super.configure();
		if (!"parent".equals(getFrom()) && !"input".equals(getFrom())) {
			throw new ConfigurationException("from should either be parent or input");
		}
		dtapStage=AppConstants.getInstance().getResolvedProperty("dtap.stage");
		dtapSide=AppConstants.getInstance().getResolvedProperty("dtap.side");
	}

	@Override
	public PipeRunResult doPipe(Message message, IPipeLineSession session) throws PipeRunException {
		String result = null;
		Adapter adapter;
		try {
			if ("input".equals(getFrom())) {
				String adapterName = message.asString();
				adapter = getAdapter().getConfiguration().getIbisManager().getRegisteredAdapter(adapterName);
				if (adapter == null) {
					throw new PipeRunException(this, "Could not find adapter: " + adapterName);
				}
			} else {
				adapter = getPipeLine().getAdapter();
			}
		} catch (IOException e) {
			throw new PipeRunException(this, "Could not determine adapter name", e); 
		}
		try {
			String generationInfo = "at " + RestListenerUtils.retrieveRequestURL(session);
			Wsdl wsdl = new Wsdl(adapter.getPipeLine(), generationInfo);
			wsdl.init();
			ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
			wsdl.wsdl(outputStream, null);
			result = outputStream.toString(StreamUtil.DEFAULT_INPUT_STREAM_ENCODING);
		} catch (Exception e) {
			throw new PipeRunException(this, "Could not generate WSDL for adapter [" + adapter.getName() + "]", e); 
		}
		return new PipeRunResult(getForward(), result);
	}

	public String getFrom() {
		return from;
	}

	@IbisDoc({"either parent (adapter of pipeline which contains this pipe) or input (name of adapter specified by input of pipe)", "parent"})
	public void setFrom(String from) {
		this.from = from;
	}

}