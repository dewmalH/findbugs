/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2003,2004 University of Maryland
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;


import edu.umd.cs.findbugs.*;
import edu.umd.cs.findbugs.ba.ClassContext;
import edu.umd.cs.findbugs.visitclass.PreorderVisitor;
import java.util.*;
import java.util.regex.*;
import org.apache.bcel.Repository;
import org.apache.bcel.classfile.*;
import org.apache.bcel.classfile.Deprecated;

public class Naming extends PreorderVisitor implements Detector {
	String baseClassName;
	boolean classIsPublicOrProtected;

	static class MyMethod {
		final String className;
		final String methodName;
		final String methodSig;
		final boolean isStatic;

		MyMethod(String cName, String n, String s, boolean isStatic) {
			className = cName;
			methodName = n;
			methodSig = s;
			this.isStatic = isStatic;
		}

		public String getClassName() {
			return className;
		}

		@Override
                 public boolean equals(Object o) {
			if (!(o instanceof MyMethod)) return false;
			MyMethod m2 = (MyMethod) o;
			return
					className.equals(m2.className)
			        && methodName.equals(m2.methodName)
			        && methodSig.equals(m2.methodSig);
		}

		@Override
                 public int hashCode() {
			return className.hashCode()
			        + methodName.hashCode()
			        + methodSig.hashCode();
		}

		public boolean confusingMethodNames(MyMethod m) {
			if (className.equals(m.className)) return false;
			if (methodName.equalsIgnoreCase(m.methodName)
			        && !methodName.equals(m.methodName)) return true;
			if (methodSig.equals(m.methodSig)) return false;
			if (removePackageNamesFromSignature(methodSig).equals(removePackageNamesFromSignature(m.methodSig))) {
					return true;
			}
			return false;
				
		}

		@Override
        public String toString() {
			return className
			        + "." + methodName
			        + ":" + methodSig;
		}
	}


	// map of canonicalName -> trueMethodName
	HashMap<String, HashSet<String>> canonicalToTrueMapping
	        = new HashMap<String, HashSet<String>>();
	// map of canonicalName -> Set<MyMethod>
	HashMap<String, HashSet<MyMethod>> canonicalToMyMethod
	        = new HashMap<String, HashSet<MyMethod>>();

	HashSet<String> visited = new HashSet<String>();

	private BugReporter bugReporter;

	public Naming(BugReporter bugReporter) {
		this.bugReporter = bugReporter;
	}

	public void visitClassContext(ClassContext classContext) {
		classContext.getJavaClass().accept(this);
	}

	private boolean checkSuper(MyMethod m, HashSet<MyMethod> others) {
		for (MyMethod m2 : others) {
			try {
				if (m.confusingMethodNames(m2)
						&& Repository.instanceOf(m.className, m2.className)) {
					MyMethod m3 = new MyMethod(m.className, m2.methodName, m2.methodSig, m.isStatic);
					boolean r = others.contains(m3);
					if (r) continue;
					bugReporter.reportBug(new BugInstance(this, "NM_VERY_CONFUSING", HIGH_PRIORITY)
							.addClass(m.getClassName())
							.addMethod(m.getClassName(), m.methodName, m.methodSig, m.isStatic)
							.addClass(m2.getClassName())
							.addMethod(m2.getClassName(), m2.methodName, m2.methodSig, m2.isStatic));
					return true;
				}
			} catch (ClassNotFoundException e) {
			}
		}
		return false;
	}

	private boolean checkNonSuper(MyMethod m, HashSet<MyMethod> others) {
		for (MyMethod m2 : others) {
			if (m.confusingMethodNames(m2)) {
				bugReporter.reportBug(new BugInstance(this, "NM_CONFUSING", LOW_PRIORITY)
						.addClass(m.getClassName())
						.addMethod(m.getClassName(), m.methodName, m.methodSig, m.isStatic)
						.addClass(m2.getClassName())
						.addMethod(m2.getClassName(), m2.methodName, m2.methodSig, m2.isStatic));
				return true;
			}
		}
		return false;
	}


	public void report() {

	canonicalNameIterator:
	for (String allSmall : canonicalToTrueMapping.keySet()) {
		HashSet<String> s = canonicalToTrueMapping.get(allSmall);
		if (s.size() <= 1)
			continue;
		HashSet<MyMethod> conflictingMethods = canonicalToMyMethod.get(allSmall);
		for (Iterator<MyMethod> j = conflictingMethods.iterator(); j.hasNext();) {
			if (checkSuper(j.next(), conflictingMethods))
				j.remove();
		}
		for (MyMethod conflictingMethod : conflictingMethods) {
			if (checkNonSuper(conflictingMethod, conflictingMethods))
				continue canonicalNameIterator;
		}
	}
	}

	@Override
         public void visitJavaClass(JavaClass obj) {
		if (obj.isInterface()) return;
		String name = obj.getClassName();
		if (!visited.add(name)) return;
		try {
			JavaClass supers[] = Repository.getSuperClasses(obj);
			for (JavaClass aSuper : supers) {
				visitJavaClass(aSuper);
			}
		} catch (ClassNotFoundException e) {
			// ignore it
		}
		super.visitJavaClass(obj);
	}

	@Override
         public void visit(JavaClass obj) {
		String name = obj.getClassName();
		String[] parts = name.split("[$+.]");
		baseClassName = parts[parts.length - 1];
		classIsPublicOrProtected = obj.isPublic() || obj.isProtected();
		if (baseClassName.length() == 1) return;
		if(Character.isLetter(baseClassName.charAt(0))
		   && !Character.isUpperCase(baseClassName.charAt(0))
		   && baseClassName.indexOf("_") ==-1 
			)
			bugReporter.reportBug(new BugInstance(this, 
				"NM_CLASS_NAMING_CONVENTION", 
				classIsPublicOrProtected 
				? NORMAL_PRIORITY
				: LOW_PRIORITY
					)
			        .addClass(this));
		if (name.endsWith("Exception") 
		&&  (!obj.getSuperclassName().endsWith("Exception"))
		&&  (!obj.getSuperclassName().endsWith("Error"))
		&&  (!obj.getSuperclassName().endsWith("Throwable"))) {
			bugReporter.reportBug(new BugInstance(this, 
					"NM_CLASS_NOT_EXCEPTION", 
					NORMAL_PRIORITY )
				        .addClass(this));
		}
			
		super.visit(obj);
	}

	@Override
         public void visit(Field obj) {
		if (getFieldName().length() == 1) return;

		if (!obj.isFinal() 
			&& Character.isLetter(getFieldName().charAt(0))
			&& !Character.isLowerCase(getFieldName().charAt(0))
			&& getFieldName().indexOf("_") == -1
			&& Character.isLetter(getFieldName().charAt(1))
			&& Character.isLowerCase(getFieldName().charAt(1))) {
			bugReporter.reportBug(new BugInstance(this, 
				"NM_FIELD_NAMING_CONVENTION", 
				classIsPublicOrProtected 
				 && (obj.isPublic() || obj.isProtected())  
				? NORMAL_PRIORITY
				: LOW_PRIORITY)
			        .addClass(this)
			        .addVisitedField(this)
				);
		}
		}
	private final static Pattern sigType = Pattern.compile("L([^;]*/)?([^/]+;)");
	private boolean isInnerClass(JavaClass obj) {
		for(Field f : obj.getFields())
			if (f.getName().startsWith("this$")) return true;
		return false;
	}
	private boolean markedAsNotUsable(Method obj) {
		for(Attribute a : obj.getAttributes())
			if (a instanceof Deprecated) return true;
		Code code = obj.getCode();
		if (code == null) return false;
		byte [] codeBytes = code.getCode();
		if (codeBytes.length > 1 && codeBytes.length < 10) {
			int lastOpcode = codeBytes[codeBytes.length-1] & 0xff;
			if (lastOpcode != ATHROW) return false;
			for(int b : codeBytes) 
				if ((b & 0xff) == RETURN) return false;
			return true;
			}
		return false;
	}
	@Override
         public void visit(Method obj) {
		String mName = getMethodName();
		if (mName.length() == 1) return;
		if (Character.isLetter(mName.charAt(0))
			&& !Character.isLowerCase(mName.charAt(0))
			&& Character.isLetter(mName.charAt(1))
			&& Character.isLowerCase(mName.charAt(1))
			&& mName.indexOf("_") == -1 )
			bugReporter.reportBug(new BugInstance(this, 
				"NM_METHOD_NAMING_CONVENTION", 
				classIsPublicOrProtected 
				 && (obj.isPublic() || obj.isProtected())  
				? NORMAL_PRIORITY
				: LOW_PRIORITY)
			        .addClassAndMethod(this));
		String sig = getMethodSig();
		if (mName.equals(baseClassName) && sig.equals("()V")) {
			Code code = obj.getCode();
			if (code != null && !markedAsNotUsable(obj)) {
				int priority = NORMAL_PRIORITY;
				byte [] codeBytes = code.getCode();
				if (codeBytes.length > 1)
					priority--;
				if (!obj.isPrivate() && getThisClass().isPublic()) 
					priority--;
				bugReporter.reportBug( new BugInstance(this, "NM_METHOD_CONSTRUCTOR_CONFUSION", priority).addClassAndMethod(this));
				return;
			}
		}

		if (obj.isAbstract()) return;
		if (obj.isPrivate()) return;

		if (mName.equals("equal") && sig.equals("(Ljava/lang/Object;)Z")) {
			bugReporter.reportBug(new BugInstance(this, "NM_BAD_EQUAL", HIGH_PRIORITY)
			        .addClassAndMethod(this));
			return;
		}
		if (mName.equals("hashcode") && sig.equals("()I")) {
			bugReporter.reportBug(new BugInstance(this, "NM_LCASE_HASHCODE", HIGH_PRIORITY)
			        .addClassAndMethod(this));
			return;
		}
		if (mName.equals("tostring") && sig.equals("()Ljava/lang/String;")) {
			bugReporter.reportBug(new BugInstance(this, "NM_LCASE_TOSTRING", HIGH_PRIORITY)
			        .addClassAndMethod(this));
			return;
		}


		if (obj.isPrivate()
		        || obj.isStatic()
		        || mName.equals("<init>")
		)
			return;

		String trueName = mName + sig;
		String sig2 = removePackageNamesFromSignature(sig);
		String allSmall = mName.toLowerCase() + sig2;
	

		MyMethod mm = new MyMethod(getThisClass().getClassName(), mName, sig, obj.isStatic());
		{
			HashSet<String> s = canonicalToTrueMapping.get(allSmall);
			if (s == null) {
				s = new HashSet<String>();
				canonicalToTrueMapping.put(allSmall, s);
			}
			s.add(trueName);
		}
		{
			HashSet<MyMethod> s = canonicalToMyMethod.get(allSmall);
			if (s == null) {
				s = new HashSet<MyMethod>();
				canonicalToMyMethod.put(allSmall, s);
			}
			s.add(mm);
		}

	}

	private static String removePackageNamesFromSignature(String sig) {
		int end = sig.indexOf(")");
		Matcher m = sigType.matcher(sig.substring(0,end));
		return m.replaceAll("L$2") + sig.substring(end);
	}


}
