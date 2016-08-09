package com.nartex;

import java.awt.Dimension;
import java.awt.Image;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.swing.ImageIcon;

import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileItemFactory;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 
 * CHANGES
 * - adjust document root to allow relative paths (should work also in old Filemanager?
 * - optional reload parameter for config, lang file
 * 
 * @author gkallidis
 *
 */
public abstract class AbstractFM implements FileManagerI {

	protected static Properties config = null;
	protected static JSONObject language = null;
	protected Map<String, String> get = new HashMap<String, String>();
	protected Map<String, String> properties = new HashMap<String, String>();
	protected Map<String, Object> item = new HashMap<String, Object>();
	protected Map<String, String> params = new HashMap<String, String>();
	protected Path documentRoot; // make it static?
	protected Path fileManagerRoot = null; // static?
	protected String referer = "";
	protected Logger log = LoggerFactory.getLogger("filemanager");
	protected JSONObject error = null;
	protected SimpleDateFormat dateFormat;
	protected List<FileItem> files = null;
	protected boolean reload = false;
	protected String previewPath = null; //static?
	protected boolean previewPathRelative = false; // needed as it is exposed either relative or absolute

	public AbstractFM(ServletContext servletContext, HttpServletRequest request) throws IOException {
        String contextPath = request.getContextPath();
        
        Path localPath = Paths.get(servletContext.getRealPath("/")); 
        Path docRoot4FileManager = localPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
        		
        this.referer = request.getHeader("referer");
        if (referer != null) {
            this.fileManagerRoot =  docRoot4FileManager.
        			resolve(referer.substring(referer.indexOf(contextPath) + 1 + contextPath.length(), referer.indexOf("index.html")));
        // last resort and only if already      
        } else if (this.fileManagerRoot == null && request.getServletPath().indexOf("connectors") > 0) {
        	this.fileManagerRoot =  docRoot4FileManager.
        			resolve(request.getServletPath().substring(1, request.getServletPath().indexOf("connectors")));
        	// no pathInfo
        }
        log.debug("fileManagerRoot:"+ fileManagerRoot.toRealPath(LinkOption.NOFOLLOW_LINKS));

	    
		// get uploaded file list
		FileItemFactory factory = new DiskFileItemFactory();
		ServletFileUpload upload = new ServletFileUpload(factory);
		if (ServletFileUpload.isMultipartContent(request))
			try {
				files = upload.parseRequest(request);
			} catch (Exception e) { // no error handling}
			}

		this.properties.put("Date Created", null);
		this.properties.put("Date Modified", null);
		this.properties.put("Height", null);
		this.properties.put("Width", null);
		this.properties.put("Size", null);

		// kind of a hack, should not used except for super admin purposes
		if (request.getParameter("reload") != null) {
			this.reload = true;
		}
		
		// load config file		
		loadConfig();

		if (this.documentRoot == null || reload) {
			if (config.getProperty("doc_root") != null) {
				Path documentRoot = 
			    		config.getProperty("doc_root").startsWith("/") ? 
			    		Paths.get(config.getProperty("doc_root")) : 
			    	docRoot4FileManager.resolve(config.getProperty("doc_root"));
				this.documentRoot = documentRoot.normalize();		
			} else {
				this.documentRoot =  docRoot4FileManager.toRealPath(LinkOption.NOFOLLOW_LINKS);
			}
		    log.debug("final documentRoot:"+ this.documentRoot);
		}
		if (reload) {
				this.previewPath = null;	
		}

		dateFormat = new SimpleDateFormat(config.getProperty("date"));

		this.setParams();
		
		loadLanguageFile();
		
		this.reload = false;

	}


	@Override
	public JSONObject error(String msg, Throwable ex) {
		JSONObject errorInfo = new JSONObject();
		try {
			errorInfo.put("Error", msg);
			errorInfo.put("Code", "-1");
			errorInfo.put("Properties", this.properties);
		} catch (Exception e) {
			this.error("JSONObject error");
		}
		if (ex != null) {
			log.error( msg, ex ); 
		} else {
			log.error( msg); 
		}
		this.error = errorInfo;
		return error;
	}
	
	
	@Override
	public JSONObject error(String msg) {
		return error(msg, null);
	}

	@Override
	public JSONObject getError() {
		return error;
	}

	@Override
	public String lang(String key) {
		String text = "";
		try {
			text = language.getString(key);
		} catch (Exception e) {
		}
		if (text == null || text.equals("") )
			text = "Language string error on " + key;
		return text;
	}

	@Override
	public boolean setGetVar(String var, String value) {
		boolean retval = false;
		if (value == null || value == "") {
			this.error(sprintf(lang("INVALID_VAR"), var));
		} else {
			// clean first slash, as Path does not resolve it relative otherwise 
			if (var.equals("path") && value.startsWith("/")) {
				 value = value.replaceFirst("/", "");
			}
			this.get.put(var, sanitize(value));
			retval = true;
		}
		return retval;
	}


	protected boolean checkImageType() {
		return this.params
				.get("type").equals("Image")
				&& contains(config.getProperty("images"), (String)this.item.get("filetype"));
	}

	protected boolean checkFlashType() {
		return this.params
				.get("type").equals("Flash")
				&& contains(config.getProperty("flash"),  (String)this.item.get("filetype"));
	}

	@Override
	public JSONObject rename() {
		if ((this.get.get("old")).endsWith("/")) {
			this.get.put("old", (this.get.get("old")).substring(0, ((this.get.get("old")).length() - 1)));
		}
		boolean error = false;
		JSONObject array = null;
		String tmp[] = (this.get.get("old")).split("/");
		String filename = tmp[tmp.length - 1];
		int pos = this.get.get("old").lastIndexOf("/");
		String path = (this.get.get("old")).substring(0, pos + 1);
		Path fileFrom = null;
		Path fileTo = null;
		try {
			fileFrom = this.documentRoot.resolve(path).resolve(filename);
			fileTo = this.documentRoot.resolve(path).resolve( this.get.get("new"));
			if (fileTo.toFile().exists()) {
				if (fileTo.toFile().isDirectory()) {
					this.error(sprintf(lang("DIRECTORY_ALREADY_EXISTS"), this.get.get("new")));
					error = true;
				} else { // fileTo.isFile
					// Files.isSameFile(fileFrom, fileTo);
					this.error(sprintf(lang("FILE_ALREADY_EXISTS"), this.get.get("new")));
					error = true;
				}
			} else {
				//if (fileFrom.equals(fileTo));
				Files.move(fileFrom, fileTo, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (Exception e) {
			if (fileFrom.toFile().isDirectory()) {
				this.error(sprintf(lang("ERROR_RENAMING_DIRECTORY"), filename + "#" + this.get.get("new")),e);
			} else {
				this.error(sprintf(lang("ERROR_RENAMING_FILE"), filename + "#" + this.get.get("new")),e);
			}
			error = true;
		}
		if (!error) {
			array = new JSONObject();
			try {
				array.put("Error", "");
				array.put("Code", 0);
				array.put("Old Path", this.get.get("old"));
				array.put("Old Name", filename);
				array.put("New Path", path + this.get.get("new"));
				array.put("New Name", this.get.get("new"));
			} catch (Exception e) {
				this.error("JSONObject error");
			}
		}
		return array;
	}

	@Override
	public JSONObject delete() {
		JSONObject array = null;
		File file = this.documentRoot.resolve( this.get.get("path")).toFile();
				//new File(this.documentRoot + this.get.get("path"));
		if (file.isDirectory()) {
			array = new JSONObject();
			this.unlinkRecursive(this.documentRoot.resolve( this.get.get("path")).toFile(), true);
			try {
				array.put("Error", "");
				array.put("Code", 0);
				array.put("Path", this.get.get("path"));
			} catch (Exception e) {
				this.error("JSONObject error");
			}
		} else if (file.exists()) {
			array = new JSONObject();
			if (file.delete()) {
				try {
					array.put("Error", "");
					array.put("Code", 0);
					array.put("Path", this.get.get("path"));
				} catch (Exception e) {
					this.error("JSONObject error");
				}
			} else
				this.error(sprintf(lang("ERROR_DELETING FILE"), this.get.get("path")));
			return array;
		} else {
			this.error(lang("INVALID_DIRECTORY_OR_FILE"));
		}
		return array;
	}


	@Override
	public JSONObject addFolder() {
		JSONObject array = null;
		String allowed[] = { "-", " " };
		LinkedHashMap<String, String> strList = new LinkedHashMap<String, String>();
		strList.put("fileName", this.get.get("name"));
		String filename = cleanString(strList, allowed).get("fileName");
		if (filename.length() == 0) // the name existed of only special
									// characters
			this.error(sprintf(lang("UNABLE_TO_CREATE_DIRECTORY"), this.get.get("name")));
		else {
			File file = this.documentRoot.resolve(this.get.get("path")).resolve(filename).toFile();
			if (file.isDirectory()) {
				this.error(sprintf(lang("DIRECTORY_ALREADY_EXISTS"), filename));
			} else if (!file.mkdir()) {
				this.error(sprintf(lang("UNABLE_TO_CREATE_DIRECTORY"), filename));
			} else {
				try {
					array = new JSONObject();
					array.put("Parent", this.get.get("path"));
					array.put("Name", filename);
					array.put("Error", "");
					array.put("Code", 0);
				} catch (Exception e) {
					this.error("JSONObject error");
				}
			}
		}
		return array;
	}



	protected void readFile(HttpServletResponse resp, File file) {
		OutputStream os = null;
		FileInputStream fis = null;
		try {
			os = resp.getOutputStream();
			fis = new FileInputStream(file);
			byte fileContent[] = new byte[(int) file.length()];
			fis.read(fileContent);
			os.write(fileContent);
		} catch (Exception e) {
			this.error(sprintf(lang("INVALID_DIRECTORY_OR_FILE"), file.getName()));
		} finally {
			try {
				if (os != null)
					os.close();
			} catch (Exception e2) {
			}
			try {
				if (fis != null)
					fis.close();
			} catch (Exception e2) {
			}
		}
	}

	@Override
	public void preview(HttpServletResponse resp) {
		File file =this.documentRoot.resolve(this.get.get("path")).toFile();
		if (this.get.get("path") != null && file.exists()) {
			resp.setHeader("Content-type", "image/" + getFileExtension(file.getName()));
			resp.setHeader("Content-Transfer-Encoding", "Binary");
			resp.setHeader("Content-length", "" + file.length());
			resp.setHeader("Content-Disposition", "inline; filename=\"" + getFileBaseName(file.getName()) + "\"");
			// handle caching
			resp.setHeader("Pragma", "no-cache");
			resp.setHeader("Expires", "0");
			resp.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
			readFile(resp, file);
		} else {
			error(sprintf(lang("FILE_DOES_NOT_EXIST"), this.get.get("path")));
		}
	}

	protected String getFileBaseName(String filename) {
		String retval = filename;
		int pos = filename.lastIndexOf(".");
		if (pos > 0)
			retval = filename.substring(0, pos);
		return retval;
	}

	protected String getFileExtension(String filename) {
		String retval = filename;
		int pos = filename.lastIndexOf(".");
		if (pos > 0)
			retval = filename.substring(pos + 1);
		return retval;
	}

	protected void setParams() {
		if (this.referer != null) {
			String[] tmp = this.referer.split("\\?");
			String[] params_tmp = null;
			LinkedHashMap<String, String> params = new LinkedHashMap<String, String>();
			if (tmp.length > 1 && tmp[1] != "") {
				params_tmp = tmp[1].split("&");
				for (int i = 0; i < params_tmp.length; i++) {
					tmp = params_tmp[i].split("=");
					if (tmp.length > 1 && tmp[1] != "") {
						params.put(tmp[0], tmp[1]);
					}
				}
			}
			this.params = params;
		}
	}

	@Override
	public String getConfigString(String key) {
		return config.getProperty(key);
	}

	public Path getDocumentRoot() {
		return this.documentRoot;
	}


	protected boolean isImage(String fileName) {
		boolean isImage = false;
		String ext = "";
		int pos = fileName.lastIndexOf(".");
		if (pos > 1 && pos != fileName.length()) {
			ext = fileName.substring(pos + 1);
			isImage = contains(config.getProperty("images"), ext);
		}
		return isImage;
	}

	protected boolean contains(String where, String what) {
		boolean retval = false;
	
		String[] tmp = where.split(",");
		for (int i = 0; i < tmp.length; i++) {
			if (what.equalsIgnoreCase(tmp[i])) {
				retval = true;
				break;
			}
		}
		return retval;
	}

	protected Dimension getImageSize(String path) {
		Dimension imgData = new Dimension();
		Image img = new ImageIcon(path).getImage();
		imgData.height = img.getHeight(null);
		imgData.width = img.getWidth(null);
		return imgData;
	}

	protected void unlinkRecursive(File dir, boolean deleteRootToo) {
		//File dh = new File(dir);
		File fileOrDir = null;
	
		if (dir.exists()) {
			String[] objects = dir.list();
			for (int i = 0; i < objects.length; i++) {
				fileOrDir = new File(dir + "/" + objects[i]);
				if (fileOrDir.isDirectory()) {
					if (!objects[i].equals(".") && !objects[i].equals("..")) {
						unlinkRecursive(new File(dir + "/" + objects[i]), true);
					}
				}
				fileOrDir.delete();
	
			}
			if (deleteRootToo) {
				dir.delete();
			}
		}
	}

	protected HashMap<String, String> cleanString(HashMap<String, String> strList, String[] allowed) {
		String allow = "";
		HashMap<String, String> cleaned = null;
		Iterator<String> it = null;
		String cleanStr = null;
		String key = null;
		for (int i = 0; i < allowed.length; i++) {
			allow += "\\" + allowed[i];
		}
	
		if (strList != null) {
			cleaned = new HashMap<String, String>();
			it = strList.keySet().iterator();
			while (it.hasNext()) {
				key = it.next();
				cleanStr = strList.get(key).replaceAll("[^{" + allow + "}_a-zA-Z0-9]", "");
				cleaned.put(key, cleanStr);
			}
		}
		return cleaned;
	}

	protected String sanitize(String var) {
		String sanitized = var.replaceAll("\\<.*?>", "");
		sanitized = sanitized.replaceAll("http://", "");
		sanitized = sanitized.replaceAll("https://", "");
		sanitized = sanitized.replaceAll("\\.\\./", "");
		return sanitized;
	}

	protected String checkFilename(String path, String filename, int i) {
		File file = new File(path + filename);
		String i2 = "";
		String[] tmp = null;
		if (!file.exists()) {
			return filename;
		} else {
			if (i != 0)
				i2 = "" + i;
			tmp = filename.split(i2 + "\\.");
			i++;
			filename = filename.replace(i2 + "." + tmp[tmp.length - 1], i + "." + tmp[tmp.length - 1]);
			return this.checkFilename(path, filename, i);
		}
	}

	protected void loadConfig() {
		InputStream is;
		if (config == null || reload) {
			try {
				//log.info("reading from " + this.fileManagerRoot.resolve("connectors/jsp/config.properties").toString());
				is = new FileInputStream( this.fileManagerRoot.resolve("connectors/jsp/config.properties").toString());
				config = new Properties();
				config.load(is);
			} catch (Exception e) {
				error("Error loading config file "+ this.fileManagerRoot.resolve("connectors/jsp/config.properties"));
			}
		}
	}

	protected String sprintf(String text, String params) {
		String retText = text;
		String[] repl = params.split("#");
		for (int i = 0; i < repl.length; i++) {
			retText = retText.replaceFirst("%s", repl[i]);
		}
		return retText;
	}
	
	/* (non-Javadoc)
	 * @see com.nartex.FileManagerI#log(java.lang.String, java.lang.String)
	 */
	@Override
	public void log(String msg) {
		log.debug(msg);
	}

}