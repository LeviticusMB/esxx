/*
     ESXX - The friendly ECMAscript/XML Application Server
     Copyright (C) 2007-2015 Martin Blom <martin@blom.org>

     This program is free software: you can redistribute it and/or
     modify it under the terms of the GNU General Public License
     as published by the Free Software Foundation, either version 3
     of the License, or (at your option) any later version.

     This program is distributed in the hope that it will be useful,
     but WITHOUT ANY WARRANTY; without even the implied warranty of
     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
     GNU General Public License for more details.

     You should have received a copy of the GNU General Public License
     along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package org.esxx.util;

import java.io.File;
import java.io.FilenameFilter;
import java.util.Date;
import java.util.regex.Pattern;
import org.esxx.ESXX;
import org.esxx.ESXXException;
import org.mozilla.javascript.*;

public abstract class JS {
  public static Scriptable evaluateObjectExpr(String object_expr, Scriptable scope) {
    Scriptable object = scope;

    if (object_expr != null) {
      String path[] = dotPattern.split(object_expr, 0);

      for (String p : path) {
	Object o = ScriptableObject.getProperty(object, p);

	if (o == Scriptable.NOT_FOUND || !(o instanceof Scriptable)) {
	  return null;
	}

	object = (Scriptable) o;
      }
    }

    return object;
  }

  public static Object callJSMethod(String expr,
				    Object[] args, String identifier,
				    Context cx, Scriptable scope) {
    String object;
    String method;

    int dot = expr.lastIndexOf('.');

    if (dot == -1) {
      object = null;
      method = expr;
    }
    else {
      object = expr.substring(0, dot);
      method = expr.substring(dot + 1);
    }

    return callJSMethod(object, method, args, identifier, cx, scope, true);
  }

  public static Object callJSMethod(String object_expr, String method,
				    Object[] args, String identifier,
				    Context cx, Scriptable scope,
				    boolean throw_if_not_found) {
    Scriptable object = evaluateObjectExpr(object_expr, scope);
    String     function_name = object == scope ? method : object_expr + "." + method;

    if (object == null) {
      throw new ESXXException(object_expr + " cannot be evalualted.");
    }

    Object m = ScriptableObject.getProperty(object, method);

    if (m == Scriptable.NOT_FOUND) {
      if (throw_if_not_found) {
	throw new ESXXException(identifier + " method " + function_name + " not found");
      }
      else {
	return null;
      }
    }

    if (!(m instanceof Function)) {
      throw new ESXXException(identifier + " " + function_name + " is not a function.");
    }

    return ((Function) m).call(cx, scope, object, args);
  }


  public static void dumpScriptState(Scriptable scope) {
    System.err.println("Scope trace:");
    printScopeTrace(scope);
    System.err.println();
    System.err.flush();
  }

  public static void dumpScope(Scriptable scope) {
    System.err.println("Dump of scope " + scope);

    for (Object i : ((ScriptableObject) scope).getAllIds()) {
      if (i instanceof Number) {
	System.err.println(i + ": " + scope.get((Integer) i, scope));
      }
      else {
	System.err.println("'" + i + "': " + scope.get((String) i, scope));
      }
    }
  }


  public static void printScriptStackTrace() {
    StackTraceElement[] trace = Thread.currentThread().getStackTrace();

    JSFilenameFilter filter = new JSFilenameFilter ();

    for (StackTraceElement e : trace) {
      File f = new File(e.getFileName());
      if (filter.accept(f.getParentFile(), f.getName())) {
	System.err.println(e);
      }
    }
  }


  public static void printScopeTrace(Scriptable scope) {
    if (scope == null) {
      return;
    }

    printPrototypeTrace(scope);
    printScopeTrace(scope.getParentScope());
  }


  public static void printPrototypeTrace(Scriptable scope) {
    if (scope == null) {
      System.err.println();
    }
    else {
      System.err.print(defaultToString(scope) + " ");
      printPrototypeTrace(scope.getPrototype());
    }
  }

  public static void printObject(Context cx, Scriptable scope, Object object) {
    if (object instanceof RhinoException) {
      RhinoException ex = (RhinoException) object;

      do {
	System.out.println("-> " + ex.getClass().getSimpleName() + " from " 
			   + ex.sourceName() + ", line " + ex.lineNumber()
			   + ", column " + ex.columnNumber()
			   + (ex.lineSource() != null ?
			      ", near " + ex.lineSource() : "")
			   + ":");

	if (object instanceof JavaScriptException) {
	  object = ((JavaScriptException) object).getValue();
	}
	else if (object instanceof WrappedException) {
	  object = ((WrappedException) object).getWrappedException();
	}
	else {
	  object = ex.details();
	}
      }
      while (object instanceof JavaScriptException || object instanceof WrappedException);
    }

    while (object instanceof Wrapper) {
      System.out.println("-> " + object.getClass().getSimpleName() + ":");
      object = ((Wrapper) object).unwrap();
    }

    if (object instanceof Throwable) {
      System.out.println("-> " + object.getClass().getSimpleName() + ":");
      object = ((Throwable) object).getMessage();
    }

    if (object instanceof String) {
      System.out.println("-> `" + object + "\u00b4");
    }
    else if (object instanceof Number || object instanceof Boolean) {
      System.out.println("-> " + object);
    }
    else if (object == Context.getUndefinedValue()) {
      System.out.println("-> undefined");
    }
    else if (object instanceof Scriptable) {
      Scriptable thiz = (Scriptable) object;

      System.out.println("-> JavaScript " + thiz.getClassName() + ":");

      Object to_source = ScriptableObject.getProperty(thiz, "toSource");

      if (to_source == Scriptable.NOT_FOUND) {
	to_source = ScriptableObject.getProperty(thiz, "toXMLString"); // Fallback for XMLList
      }

      if (to_source == Scriptable.NOT_FOUND) {
	to_source = ScriptableObject.getProperty(thiz, "toString");
      }

      if (to_source instanceof Function) {
	Function ts = (Function) to_source;

	try {
	  System.out.println("-> " 
			     + ts.call(cx, scope, thiz, Context.emptyArgs).toString().trim());
	}
	catch (Exception ignored) {
	  printPlainObject(object);
	}
      }
      else if (object instanceof Function) {
	System.out.println("-> " + cx.decompileFunction((Function) object, 3).trim());
      }
      else {
	printPlainObject(object);
      }
    }
    else {
      printPlainObject(object);
    }
  }

  private static void printPlainObject(Object object) {
    try {
      if (object.getClass().getMethod("toString").getDeclaringClass() != Object.class) {
	System.out.println("-> " + defaultToString(object) + ":");
      }
    }
    catch (Exception ignored) {}

    System.out.println("-> " + object);
  }

  public static String defaultToString(Object o) {
    return o.getClass().getName() + "@" + Integer.toHexString(o.hashCode());
  }

  public static String toStringOrNull(Object prop) {
    if (prop != null &&
	prop != Scriptable.NOT_FOUND &&
	prop != Context.getUndefinedValue()) {
      return Context.toString(prop);
    }
    else {
      return null;
    }
  }

  public static String toStringOrNull(Scriptable scope, String name) {
    return toStringOrNull(scope.get(name, scope));
  }

  public static boolean toBoolean(Object prop) {
    return prop == Scriptable.NOT_FOUND ? false : Context.toBoolean(prop);
  }

  public static boolean toBoolean(Scriptable scope, String name) {
    return toBoolean(scope.get(name, scope));
  }

  public static Object unwrap(Object object) {
    // Unwrap wrapped objects and exceptions
    while (true) {
      if (object instanceof JavaScriptException) {
	object = ((JavaScriptException) object).getValue();
      }
      else if (object instanceof WrappedException) {
	object = ((WrappedException) object).getWrappedException();
      }
      else if (object instanceof Wrapper) {
	object = ((Wrapper) object).unwrap();
      }
      else {
	break;
      }
    }
    
    return object;
  }

  public static Object toJavaObject(Object object) {
    object = unwrap(object);

    // Convert to "primitive" types
    if (object instanceof Scriptable) {
      Scriptable js = (Scriptable) object;

      if (js instanceof org.mozilla.javascript.xml.XMLObject) {
	if (!"XMLList".equals(js.getClassName()) || !js.has(1, js)) {
	  object = ESXX.e4xToDOM(js);
	}
      }
      else if ("Date".equals(js.getClassName())) {
	object = Context.jsToJava(js, Date.class);
      }
    }

    return object;
  }

  public static Scriptable toJSArray(Context cx, Scriptable scope, Object[] array) {
    Object[] plain = new Object[array.length];

    for (int i = 0; i < plain.length; ++i) {
      plain[i] = array[i];
    }

    return cx.newArray(scope, plain);
  }

  public static class JSFilenameFilter
    implements FilenameFilter {
    public boolean accept(File dir, String name) {
      boolean is_java = name.matches(".*\\.java");
      return !is_java;
    }
  }

  private static Pattern dotPattern = Pattern.compile("\\.");
}
