package org.apache.maven.proxy;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.maven.fetch.exceptions.FetchException;
import org.apache.maven.fetch.exceptions.ResourceNotFoundFetchException;
import org.apache.maven.fetch.util.IOUtility;

/**
 * @author  Ben Walding
 * @version $Id$
 */
public class RepositoryServlet extends HttpServlet
{
    /** log4j logger */
    private static final org.apache.log4j.Logger LOGGER = org.apache.log4j.Logger.getLogger(RepositoryServlet.class);

    private List retrievers = new ArrayList();
    private File baseDir;

    /* (non-Javadoc)
     * @see javax.servlet.http.HttpServlet#getLastModified(javax.servlet.http.HttpServletRequest)
     */
    protected long getLastModified(HttpServletRequest request)
    {
        LOGGER.info("Checking getLastModified(): " + request.getPathInfo());
        File f = getFileForRequest(request);
        if (f.exists() && f.isFile())
        {
            return f.lastModified();
        }
        else
        {
            return super.getLastModified(request);
        }
    }

    public void init() throws ServletException
    {
        Properties props = (Properties) getServletContext().getAttribute("properties");
        String remote;
        int i = 1;
        boolean done = false;

        baseDir = new File(props.getProperty(ProxyProperties.REPOSITORY_LOCAL));

        if (!baseDir.exists())
        {
            LOGGER.info("Local Repository (" + baseDir.getAbsolutePath() + ") does not exist");
        }

        while(!done)
        {
            DefaultRetrievalComponent rc = new DefaultRetrievalComponent();

            remote = props.getProperty(ProxyProperties.REPOSITORY_REMOTE + "." + i);
            if(remote == null) {
                done = true;
                break;
            }

            LOGGER.info("Adding " + remote);
            rc.setBaseUrl(remote);
            configureProxy(rc, props);
            retrievers.add(rc);
            i++;
        }
    }

    public File getFileForRequest(HttpServletRequest request)
    {
        return new File(baseDir, request.getPathInfo());
    }

    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        String pathInfo = request.getPathInfo();
        LOGGER.info("Received request: " + pathInfo);

        if (pathInfo.endsWith("/"))
        {
            PrintWriter pw = response.getWriter();

            pw.println("<html>");
            pw.println("<body>");
            File dir = new File(baseDir, pathInfo);
            File[] files = dir.listFiles();
            if (files == null) {
                files = new File[0];
            }
            for (int i = 0; i < files.length; i++)
            {
                pw.println("<br/>");
                pw.println("<a href='" + pathInfo + files[i].getName() + "/'>" + files[i].getName() + "</a>");
            }
            pw.println("</body>");
            pw.println("</html>");
            return;
        }
        try
        {
            boolean done = false;
            for (int i = 0; i < retrievers.size(); i++)
            {
                DefaultRetrievalComponent rc = (DefaultRetrievalComponent) retrievers.get(i);

                try
                {
                    File f = new File(baseDir, request.getPathInfo());
                    f.getParentFile().mkdirs();
                    if (f.exists())
                    {
                        LOGGER.info("Retrieving from cache: " + f.getAbsolutePath());
                    }
                    else
                    {
                        rc.retrieveArtifact(f, request.getPathInfo());
                    }

                    InputStream is = new FileInputStream(f);

                    //TODO could tailor the mime type
                    response.setContentType("application/octet-stream");
                    OutputStream os = response.getOutputStream();
                    IOUtility.transferStream(is, os);
                    IOUtility.close(os);
                    IOUtility.close(is);
                }
                catch (ResourceNotFoundFetchException ex)
                {
                    // if not found, just move on
                }
            }

            if (!done)
            {
                LOGGER.warn("Could not find " + request.getPathInfo());
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "Could not find " + request.getPathInfo());
            }
        }
        catch (ResourceNotFoundFetchException e)
        {
            //This is not a huge error
            LOGGER.info("Couldn't find upstream resource : " + e.getMessage());
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
        catch (FetchException e)
        {
            e.printStackTrace();
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, e.getLocalizedMessage());
        }
    }

    private void configureProxy(DefaultRetrievalComponent rc, Properties props)
    {
        String host = props.getProperty(ProxyProperties.PARENT_PROXY_HOST);
        if (host != null)
        {
            rc.setProxyHost(props.getProperty(ProxyProperties.PARENT_PROXY_HOST));
            rc.setProxyPort(Integer.parseInt(props.getProperty(ProxyProperties.PARENT_PROXY_PORT)));
            rc.setProxyUsername(props.getProperty(ProxyProperties.PARENT_PROXY_USERNAME));
            rc.setProxyPassword(props.getProperty(ProxyProperties.PARENT_PROXY_PASSWORD));
        }
    }
}
