/*
 * Created on Nov 16, 2015
 * Created by Paul Gardner
 * 
 * Copyright 2015 Azureus Software, Inc.  All rights reserved.
 * 
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or 
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307, USA.
 */


package org.parg.azureus.plugins.jscripter;

import java.io.IOException;
import java.io.Writer;
import java.util.HashMap;
import java.util.Map;

import javax.script.Bindings;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import com.biglybt.core.config.COConfigurationManager;
import com.biglybt.core.util.AESemaphore;
import com.biglybt.core.util.Debug;
import com.biglybt.pif.PluginException;
import com.biglybt.pif.PluginInterface;
import com.biglybt.pif.UnloadablePlugin;
import com.biglybt.pif.logging.LoggerChannel;
import com.biglybt.pif.logging.LoggerChannelListener;
import com.biglybt.pif.ui.UIManager;
import com.biglybt.pif.ui.components.UITextArea;
import com.biglybt.pif.ui.config.ActionParameter;
import com.biglybt.pif.ui.config.BooleanParameter;
import com.biglybt.pif.ui.config.Parameter;
import com.biglybt.pif.ui.config.ParameterListener;
import com.biglybt.pif.ui.config.StringParameter;
import com.biglybt.pif.ui.model.BasicPluginConfigModel;
import com.biglybt.pif.ui.model.BasicPluginViewModel;
import com.biglybt.pif.utils.LocaleUtilities;
import com.biglybt.pif.utils.ScriptProvider;

public class 
JScripterPlugin 
	implements UnloadablePlugin
{
	private PluginInterface			plugin_interface;
	private LoggerChannel 			log;
	private BasicPluginConfigModel 	config_model;
	private BasicPluginViewModel	view_model;
	private LocaleUtilities			loc_utils;
	
	private UITextArea 				text_area;
	private StringParameter			script_param;
	private BooleanParameter		log_calls;
	
	
	private boolean					engine_load_attempted;
	private volatile ScriptEngine 	engine;

	private boolean					unloaded;
	
	private static AESemaphore		init_sem = new AESemaphore("jscripter:init");
	
	private static volatile JScripterPlugin		plugin_instance;
	
	private static ScriptProvider		provider;

	public static void 
	load(
		PluginInterface plugin_interface )
	{	
		try{
			provider = 
				new ScriptProvider()
				{
					@Override
					public String
					getProviderName()
					{
						return( "nashorn" );
					}
				
					@Override
					public String
					getScriptType()
					{
						return( ST_JAVASCRIPT );
					}
				
					@Override
					public Object
					eval(
						String					script,
						Map<String,Object>		bindings )
						
						throws Exception
					{
						JScripterPlugin inst = plugin_instance;
						
						if ( inst == null ){
							
							if ( !init_sem.reserve( 60*1000 )){
							
								throw( new Exception( "Plugin initialisation timeout" ));
							}
							
							inst = plugin_instance;
							
							if ( inst == null ){
								
								throw( new Exception( "Plugin initialisation failed" ));
							}
						}
						
						return( inst.evaluateScript( script, bindings, false ));
					}
				};
					
			plugin_interface.getUtilities().registerScriptProvider( provider );
			
		}catch( Throwable e ){
			
			Debug.out( e );
		}
	}
	
	@Override
	public void 
	initialize(
		PluginInterface _plugin_interface )
		
		throws PluginException 
	{	
		try{
			plugin_instance = this;
			
			plugin_interface = _plugin_interface;
			
			log	= plugin_interface.getLogger().getTimeStampedChannel( "JScripter");
			
			final UIManager	ui_manager = plugin_interface.getUIManager();
			
			loc_utils = plugin_interface.getUtilities().getLocaleUtilities();
	
			view_model = ui_manager.createBasicPluginViewModel( loc_utils.getLocalisedMessageText( "azjscripter.name" ));
	
			view_model.getActivity().setVisible( false );
			view_model.getProgress().setVisible( false );
										
			config_model = ui_manager.createBasicPluginConfigModel( "plugins", "azjscripter.name" );
	
			view_model.setConfigSectionID( "azjscripter.name" );
	
			config_model.addHyperlinkParameter2( "azjscripter.wiki.link", loc_utils.getLocalisedMessageText( "azjscripter.wiki.link.url" ));

			script_param = config_model.addStringParameter2( "azjscripter.script", "azjscripter.script",  "");
			
			script_param.setMultiLine( 20 );
			
			ActionParameter	exec = config_model.addActionParameter2( "azjscripter.load", "azjscripter.exec" );
			
			text_area = config_model.addTextArea( "azjscripter.log");
	
			exec.addListener(
					new ParameterListener() 
					{
						@Override
						public void
						parameterChanged(
							Parameter param ) 
						{
							try{
								COConfigurationManager.setDirty();
								
								evaluateScript( script_param.getValue(), true );
								
							}catch( Throwable e ){
								
								log( e );
							}
						}
					});
			
			
			log_calls = config_model.addBooleanParameter2( "azjscripter.log.calls.enable", "azjscripter.log.calls.enable", true );

			ActionParameter	clear = config_model.addActionParameter2( "azjscripter.clear.log", "azjscripter.clear" );
			
			clear.addListener(
					new ParameterListener() 
					{
						@Override
						public void
						parameterChanged(
							Parameter param ) 
						{
							text_area.setText( "" );
						}
					});
			
			config_model.addLabelParameter2( "azjscripter.new.info" );
			
			ActionParameter	new_engine = config_model.addActionParameter2( "azjscripter.new.engine", "azjscripter.new" );
			
			new_engine.addListener(
					new ParameterListener() 
					{
						@Override
						public void
						parameterChanged(
							Parameter param ) 
						{
							engine 					= null;
							engine_load_attempted 	= false;
							
							getEngine( true );
						}
					});
			log.addListener(
					new LoggerChannelListener()
					{
						@Override
						public void
						messageLogged(
							int		type,
							String	content )
						{
							if ( !content.endsWith( "\n" )){
								content += "\n";
							}
							view_model.getLogArea().appendText( content );
						}
						
						@Override
						public void
						messageLogged(
							String		str,
							Throwable	error )
						{
							view_model.getLogArea().appendText( str + "\n" );
							view_model.getLogArea().appendText( error.toString() + "\n" );
						}
					});
		}finally{
			
			init_sem.releaseForever();
		}
		
			// see if we need to initialise the script engine now
		
		String script = script_param.getValue().trim();
		
		if ( script.length() > 0 ){
			
			try{
				getEngine( true );
				
			}catch( Throwable e ){
				
				log( e );
			}
		}
	}
	
	private Object
	evaluateScript(
		String		script,
		boolean		is_general_script )
		
		throws Exception
	{
		Map<String,Object> bindings = new HashMap<String,Object>();
		
		bindings.put( "intent", "Load" );
		
		return( evaluateScript(script, bindings, is_general_script ));
	}
	
	private void
	log(
		String		str )
	{		
		log.log( str );
		
		if ( !str.endsWith( "\n" )){
			
			str += "\n";
		}
		
		text_area.appendText( str );
	}
	
	private void
	log(
		Throwable	e )
	{
		text_area.appendText( e.getMessage());
		
		log.log( e );
	}
	
	private void
	log(
		String		str,
		Throwable	e )
	{
		text_area.appendText( str + ": " + e.getMessage());
		
		log.log( str, e );
	}
	
	private synchronized Object
	evaluateScript(
		String				script,
		Map<String,Object>	bindings_in,
		boolean				is_general_script )
		
		throws Exception
	{
		if ( unloaded ){
			
			throw( new Exception( "Plugin unloaded" ));
		}
		
		ScriptEngine	engine = getEngine( !is_general_script );
		
		if ( engine == null ){
			
			throw( new Exception( "JavaScript engine unavailable" ));
		}
		
		String intent = (String)bindings_in.get( "intent" );
		
		Bindings bindings = engine.getBindings( ScriptContext.ENGINE_SCOPE );
		
		bindings.putAll( bindings_in );
		
		Object result = engine.eval(script);
		
		if ( log_calls.getValue()){
		
			String str = (intent==null?"?":intent) + " -> " + result;
		
			log( str );
		}
		
		return( result );
	}
	
	private synchronized ScriptEngine
	getEngine(
		boolean	eval_general_script )
	{
		if ( engine != null || engine_load_attempted ){
			
			return( engine );
		}
		
		engine_load_attempted = true;
		
		ScriptEngineManager engineManager = new ScriptEngineManager();
		
		engine = engineManager.getEngineByName( "nashorn" );
		
		if ( engine != null ){

			try{
				engine.getContext().setWriter( 
					new Writer()
					{
						private String buffer = "";
						
						@Override
						public void 
						write(
							char[] 		cbuf, 
							int 		off, 
							int 		len )
								
							throws IOException 
						{
							String str = new String( cbuf, off, len );
						
							str = str.replaceAll( "\r", "" );
							
							buffer += str;
							
							int pos = buffer.lastIndexOf( "\n" );
							
							if ( pos >= 0 ){
								
								log( buffer.substring( 0, pos+1 ));
								
								buffer = buffer.substring( pos+1 );
							}
						}
						
						@Override
						public void 
						close() 
							throws IOException 
						{
							log( "stdout closed" );
						}
						
						@Override
						public void 
						flush() 
							throws IOException 
						{
							if ( buffer.length() > 0 ){
								
								log( buffer );
								
								buffer = "";
							}
						}
					});
				
				Bindings bindings = engine.getBindings( ScriptContext.ENGINE_SCOPE );
				
				bindings.put( "pi", plugin_interface );
				bindings.put( "engine", engine );
				
				engine.eval( "function loadScript( abs_path ){ engine.eval( new java.io.FileReader( abs_path )); }" );
				engine.eval( "function loadPluginScript( rel_path ){ engine.eval( new java.io.FileReader( new java.io.File( pi.getPluginDirectoryName(), rel_path ))); }" );
				
				try{
					engine.eval( "loadPluginScript( 'init.js' )" );
					
				}catch( Throwable e ){
					
					log( "Failed to load 'init.js'", e );
				}
				
				if ( eval_general_script ){
					
					String initial_script = script_param.getValue().trim();
					
					if ( initial_script.length() > 0 ){
						
						engine.eval( initial_script );
					}
				}
			}catch( Throwable e ){
				
				Debug.out( e );
			}
		}
		
		return( engine );
	}
	
	@Override
	public void 
	unload() 
		throws PluginException 
	{
		unloaded	= true;
		
		if ( plugin_interface != null && provider != null ){
			
			plugin_interface.getUtilities().unregisterScriptProvider( provider );
		}
				
		if ( config_model != null ){
			
			config_model.destroy();
			
			config_model = null;
		}
		
		if ( view_model != null ){
			
			view_model.destroy();
			
			view_model = null;
		}	
		
		ScriptEngine current_engine = engine;
		
		if ( current_engine != null ){
						
			engine = null;
			
			try{
				current_engine.getContext().getWriter().close();
				
			}catch( Throwable e ){
			}
		}
		
		plugin_instance 	= null;
		init_sem 			= new AESemaphore( "jscripter:reinit" );
	}
}
