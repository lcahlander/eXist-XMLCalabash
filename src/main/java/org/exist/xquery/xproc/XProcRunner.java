/*
 *  eXist Open Source Native XML Database
 *  Copyright (C) 2013 The eXist Project
 *  http://exist-db.org
 *
 *  This program is free software; you can redistribute it and/or
 *  modify it under the terms of the GNU Lesser General Public License
 *  as published by the Free Software Foundation; either version 2
 *  of the License, or (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU Lesser General Public License for more details.
 *
 *  You should have received a copy of the GNU Lesser General Public
 *  License along with this library; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA  02110-1301  USA
 */
package org.exist.xquery.xproc;

import static com.xmlcalabash.core.XProcConstants.c_data;
import static com.xmlcalabash.util.Output.Kind.OUTPUT_STREAM;
import static java.lang.String.format;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.FileNotFoundException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLConnection;
import java.net.MalformedURLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.lang.reflect.Array;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactoryConfigurationError;
import javax.xml.transform.URIResolver;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.sax.TemplatesHandler;
import javax.xml.transform.sax.TransformerHandler;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;

import net.sf.saxon.s9api.QName;
import net.sf.saxon.s9api.SaxonApiException;
import net.sf.saxon.s9api.Serializer;
import net.sf.saxon.s9api.XdmNode;

import org.apache.commons.io.output.ByteArrayOutputStream;
import org.exist.storage.DBBroker;
import org.exist.util.io.Resource;
import org.xml.sax.InputSource;
import org.exist.xmldb.XmldbURI;
import org.exist.security.Permission;
import org.exist.security.PermissionDeniedException;
import org.exist.dom.persistent.DocumentImpl;

import com.xmlcalabash.core.XProcConfiguration;
import com.xmlcalabash.core.XProcException;
import com.xmlcalabash.core.XProcRuntime;
import com.xmlcalabash.io.ReadableData;
import com.xmlcalabash.io.ReadablePipe;
import com.xmlcalabash.io.WritableDocument;
import com.xmlcalabash.model.RuntimeValue;
import com.xmlcalabash.model.Serialization;
import com.xmlcalabash.runtime.XPipeline;
import com.xmlcalabash.util.Input;
import com.xmlcalabash.util.Output;
import com.xmlcalabash.util.S9apiUtils;
import com.xmlcalabash.util.Output.Kind;

/**
 * @author <a href="mailto:shabanovd@gmail.com">Dmitriy Shabanov</a>
 *
 */
public class XProcRunner {
    
    private static Logger logger = LogManager.getLogger(XProcRunner.class.getName());

    public static final String run(URI staticBaseURI, DBBroker broker, UserArgs userArgs, InputStream defaultIn) throws Exception {
        XProcConfiguration config = new XProcConfiguration();
        
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        
        run(staticBaseURI, defaultIn, byteStream, userArgs, config);
        
        return byteStream.toString();
    }
    
    protected static boolean run(URI staticBaseURI, InputStream defaultIn, ByteArrayOutputStream byteStream, UserArgs userArgs, XProcConfiguration config) throws SaxonApiException, IOException, URISyntaxException {
        XProcRuntime runtime = new XProcRuntime(config);
        
        if (staticBaseURI != null) {
            runtime.setURIResolver(new ExternalResolver(staticBaseURI.toString()));
//            runtime.setStaticBaseURI(staticBaseURI);
//            runtime.setBaseURI(staticBaseURI);
        }

        boolean debug = config.debug;

        XPipeline pipeline = null;

        if (userArgs.getPipeline() != null) {
            pipeline = runtime.load(userArgs.getPipeline());
        } else if (userArgs.hasImplicitPipeline()) {
            XdmNode implicitPipeline = userArgs.getImplicitPipeline(runtime);

            if (debug) {
                System.err.println("Implicit pipeline:");

                Serializer serializer = new Serializer();

                serializer.setOutputProperty(Serializer.Property.INDENT, "yes");
                serializer.setOutputProperty(Serializer.Property.METHOD, "xml");

                serializer.setOutputStream(System.err);

                S9apiUtils.serialize(runtime, implicitPipeline, serializer);
            }

            pipeline = runtime.use(implicitPipeline);
        } else if (config.pipeline != null) {
            XdmNode doc = config.pipeline.read();
            pipeline = runtime.use(doc);
        } else {
            throw new UnsupportedOperationException("Either a pipeline or libraries and / or steps must be given");
        }

        // Process parameters from the configuration...
        for (String port : config.params.keySet()) {
            Map<QName, String> parameters = config.params.get(port);
            setParametersOnPipeline(pipeline, port, parameters);
        }

        // Now process parameters from the command line...
        for (String port : userArgs.getParameterPorts()) {
            Map<QName, String> parameters = userArgs.getParameters(port);
            setParametersOnPipeline(pipeline, port, parameters);
        }

        Set<String> ports = pipeline.getInputs();
        Set<String> userArgsInputPorts = userArgs.getInputPorts();
        Set<String> cfgInputPorts = config.inputs.keySet();
        Set<String> allPorts = new HashSet<String>();
        allPorts.addAll(userArgsInputPorts);
        allPorts.addAll(cfgInputPorts);

        // map a given input without port specification to the primary non-parameter input implicitly
        for (String port : ports) {
            if (!allPorts.contains(port) && allPorts.contains(null)
                && pipeline.getDeclareStep().getInput(port).getPrimary()
                && !pipeline.getDeclareStep().getInput(port).getParameterInput()) {

                if (userArgsInputPorts.contains(null)) {
                    userArgs.setDefaultInputPort(port);
                    allPorts.remove(null);
                    allPorts.add(port);
                }
                break;
            }
        }

        for (String port : allPorts) {
            if (!ports.contains(port)) {
                throw new XProcException("There is a binding for the port '" + port + "' but the pipeline declares no such port.");
            }

            pipeline.clearInputs(port);

            if (userArgsInputPorts.contains(port)) {
                XdmNode doc = null;
                for (Input input : userArgs.getInputs(port)) {
                    switch (input.getType()) {
                        case XML:
                            switch (input.getKind()) {
                                case URI:
                                    String uri = input.getUri();
                                    if ("-".equals(uri)) {
                                        throw new IOException("unsupported '-'");
//                                        doc = runtime.parse(new InputSource(System.in));
                                    } else {
                                        doc = runtime.parse(uri, staticBaseURI.toASCIIString());
                                    }
                                    break;

                                case INPUT_STREAM:
                                    InputStream inputStream = input.getInputStream();
                                    doc = runtime.parse(new InputSource(inputStream));
                                    inputStream.close();
                                    break;

                                default:
                                    throw new UnsupportedOperationException(format("Unsupported input kind '%s'", input.getKind()));
                            }
                            break;

                        case DATA:
                            ReadableData rd;
                            switch (input.getKind()) {
                                case URI:
                                    rd = new ReadableData(runtime, c_data, input.getUri(), input.getContentType());
                                    doc = rd.read();
                                    break;

                                case INPUT_STREAM:
                                    InputStream inputStream = input.getInputStream();
                                    rd = new ReadableData(runtime, c_data, inputStream, input.getContentType());
                                    doc = rd.read();
                                    inputStream.close();
                                    break;

                                default:
                                    throw new UnsupportedOperationException(format("Unsupported input kind '%s'", input.getKind()));
                            }
                            break;

                        default:
                            throw new UnsupportedOperationException(format("Unsupported input type '%s'", input.getType()));
                    }

                    pipeline.writeTo(port, doc);
                }
            } else {
                for (ReadablePipe pipe : config.inputs.get(port)) {
                    XdmNode doc = pipe.read();
                    pipeline.writeTo(port, doc);
                }
            }
        }

        // Implicit binding for stdin?
        String implicitPort = null;
        for (String port : ports) {
            if (!allPorts.contains(port)) {
                if (pipeline.getDeclareStep().getInput(port).getPrimary()
                        && !pipeline.getDeclareStep().getInput(port).getParameterInput()) {
                    implicitPort = port;
                }
            }
        }

        if (implicitPort != null && !pipeline.hasReadablePipes(implicitPort) && defaultIn != null) {
//            throw new XProcException("no implicitPort or it is not readable.");
            XdmNode doc = runtime.parse(new InputSource(defaultIn));
            pipeline.writeTo(implicitPort, doc);
        }

        Map<String, Output> portOutputs = new HashMap<String, Output>();

        Map<String, Output> userArgsOutputs = userArgs.getOutputs();
        for (String port : pipeline.getOutputs()) {
            // Bind to "-" implicitly
            Output output = null;

            if (userArgsOutputs.containsKey(port)) {
                output = userArgsOutputs.get(port);
            } else if (config.outputs.containsKey(port)) {
                output = new Output(config.outputs.get(port));
            } else if (userArgsOutputs.containsKey(null)
                       && pipeline.getDeclareStep().getOutput(port).getPrimary()) {
                // Bind unnamed port to primary output port
                output = userArgsOutputs.get(null);
            }

            // Look for explicit binding to "-"
            if ((output != null) && (output.getKind() == Kind.URI) && "-".equals(output.getUri())) {
                output = null;
            }

            portOutputs.put(port, output);
        }

        for (QName optname : config.options.keySet()) {
            RuntimeValue value = new RuntimeValue(config.options.get(optname), null, null);
            pipeline.passOption(optname, value);
        }

        for (QName optname : userArgs.getOptionNames()) {
            RuntimeValue value = new RuntimeValue(userArgs.getOption(optname), null, null);
            pipeline.passOption(optname, value);
        }

        pipeline.run();

        for (String port : pipeline.getOutputs()) {
            Output output;
            if (portOutputs.containsKey(port)) {
                output = portOutputs.get(port);
            } else {
                // You didn't bind it, and it isn't going to stdout, so it's going into the bit bucket.
                continue;
            }

            if ((output == null) || ((output.getKind() == OUTPUT_STREAM) && System.out.equals(output.getOutputStream()))) {
                finest(logger, null, "Copy output from " + port + " to stdout");
            } else {
                switch (output.getKind()) {
                    case URI:
                        finest(logger, null, "Copy output from " + port + " to " + output.getUri());
                        break;

                    case OUTPUT_STREAM:
                        String outputStreamClassName = output.getOutputStream().getClass().getName();
                        finest(logger, null, "Copy output from " + port + " to " + outputStreamClassName + " stream");
                        break;

                    default:
                        throw new UnsupportedOperationException(format("Unsupported output kind '%s'", output.getKind()));
                }
            }

            Serialization serial = pipeline.getSerialization(port);

            if (serial == null) {
                // Use the configuration options
                // FIXME: should each of these be considered separately?
                // FIXME: should there be command-line options to override these settings?
                serial = new Serialization(runtime, pipeline.getNode()); // The node's a hack
                for (String name : config.serializationOptions.keySet()) {
                    String value = config.serializationOptions.get(name);

                    if ("byte-order-mark".equals(name)) serial.setByteOrderMark("true".equals(value));
                    if ("escape-uri-attributes".equals(name)) serial.setEscapeURIAttributes("true".equals(value));
                    if ("include-content-type".equals(name)) serial.setIncludeContentType("true".equals(value));
                    if ("indent".equals(name)) serial.setIndent("true".equals(value));
                    if ("omit-xml-declaration".equals(name)) serial.setOmitXMLDeclaration("true".equals(value));
                    if ("undeclare-prefixes".equals(name)) serial.setUndeclarePrefixes("true".equals(value));
                    if ("method".equals(name)) serial.setMethod(new QName("", value));

                    // FIXME: if ("cdata-section-elements".equals(name)) serial.setCdataSectionElements();
                    if ("doctype-public".equals(name)) serial.setDoctypePublic(value);
                    if ("doctype-system".equals(name)) serial.setDoctypeSystem(value);
                    if ("encoding".equals(name)) serial.setEncoding(value);
                    if ("media-type".equals(name)) serial.setMediaType(value);
                    if ("normalization-form".equals(name)) serial.setNormalizationForm(value);
                    if ("standalone".equals(name)) serial.setStandalone(value);
                    if ("version".equals(name)) serial.setVersion(value);
                }
            }

            // I wonder if there's a better way...
            WritableDocument wd = null;
            if (output == null) {
                //wd = new EXistDocument(runtime, null, serial);
                wd = new WritableDocument(runtime, null, serial, byteStream);
            } else {
                switch (output.getKind()) {
                    case URI:
                        URI uri = new URI(output.getUri());

                        String filename = uri.getPath();

                        Resource resource = new Resource(filename);
                        OutputStream outfile = resource.getOutputStream();

//                        URI furi = new URI(output.getUri());
//                        String filename = furi.getPath();
//                        FileOutputStream outfile = new FileOutputStream(filename);

                        wd = new WritableDocument(runtime, filename, serial, outfile);
                        break;

                    case OUTPUT_STREAM:
                        OutputStream outputStream = output.getOutputStream();
                        wd = new WritableDocument(runtime, null, serial, outputStream);
                        break;

                    default:
                        throw new UnsupportedOperationException(format("Unsupported output kind '%s'", output.getKind()));
                }
            }

            ReadablePipe rpipe = pipeline.readFrom(port);
            while (rpipe.moreDocuments()) {
                wd.write(rpipe.read());
            }

            if (output != null) {
                wd.close();
            }
        }

        return portOutputs.containsValue(null);
    }
    
    private static void setParametersOnPipeline(XPipeline pipeline, String port, Map<QName, String> parameters) {
        if ("*".equals(port)) {
            for (QName name : parameters.keySet()) {
                pipeline.setParameter(name, new RuntimeValue(parameters.get(name)));
            }
        } else {
            for (QName name : parameters.keySet()) {
                pipeline.setParameter(port, name, new RuntimeValue(parameters.get(name)));
            }
        }
    }

    private static String message(XdmNode node, String message) {
        String baseURI = "(unknown URI)";
        int lineNumber = -1;

        if (node != null) {
            baseURI = node.getBaseURI().toASCIIString();
            lineNumber = node.getLineNumber();
            return baseURI + ":" + lineNumber + ": " + message;
        } else {
            return message;
        }

    }

//    private static void error(Logger logger, XdmNode node, String message, QName code) {
//        logger.severe(message(node, message));
//    }
//
//    private static void warning(Logger logger, XdmNode node, String message) {
//        logger.warning(message(node, message));
//    }
//
//    private static void info(Logger logger, XdmNode node, String message) {
//        logger.info(message(node, message));
//    }
//
//    private static void fine(Logger logger, XdmNode node, String message) {
//        logger.fine(message(node, message));
//    }
//
//    private static void finer(Logger logger, XdmNode node, String message) {
//        logger.finer(message(node, message));
//    }

    private static void finest(Logger logger, XdmNode node, String message) {
        logger.trace(message(node, message));
    }

    private static class ExternalResolver implements URIResolver {
        
        private String baseURI;
        
        public ExternalResolver(String base) {
            this.baseURI = base;
        }
        
        /* (non-Javadoc)
         * @see javax.xml.transform.URIResolver#resolve(java.lang.String, java.lang.String)
         */
        public Source resolve(String href, String base)
        throws TransformerException {
            URL url;
            try {
                //TODO : use dedicated function in XmldbURI
                url = new URL(baseURI + "/"  + href);
                final URLConnection connection = url.openConnection();
                return new StreamSource(connection.getInputStream());
            } catch (final MalformedURLException e) {
                return null;
            } catch (final IOException e) {
                return null;
            }
        }
    }

    
}
