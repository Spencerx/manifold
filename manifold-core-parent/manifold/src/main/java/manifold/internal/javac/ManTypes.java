package manifold.internal.javac;

import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symbol.ClassSymbol;
import com.sun.tools.javac.code.Symbol.MethodSymbol;
import com.sun.tools.javac.code.Symbol.VarSymbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.code.Type;
import com.sun.tools.javac.code.Types;
import com.sun.tools.javac.util.List;
import com.sun.tools.javac.util.Pair;
import manifold.rt.api.util.TypesUtil;
import manifold.util.JreUtil;
import manifold.util.ReflectUtil;

import javax.lang.model.element.ElementKind;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static com.sun.tools.javac.code.Flags.*;
import static com.sun.tools.javac.code.Flags.PUBLIC;

public interface ManTypes
{
  ThreadLocal<Map<Pair<String, String>, Boolean>> CACHED_PAIRS = ThreadLocal.withInitial( HashMap::new );

  Types types();

  /**
   * Is t structurally assignable to structural interface s?
   * <p/>
   * Note, this tests the _erased_ types of t and s. Handling generics here is a bridge too for, for now.
   */
  default boolean isAssignableToStructuralType( Type t, Type s )
  {
    try
    {
      return _isAssignableToStructuralType( t, s );
    }
    catch( Throwable e )
    {
      // todo: diagnose this
      //  as a diagnostic, prevent this check from causing an exception to quietly test if it is causing compilation failure
      return false;
    }
  }
  default boolean _isAssignableToStructuralType( Type t, Type s )
  {
    t = eraseTypeVars( t );
    s = eraseTypeVars( s );

    if( !(t instanceof Type.ClassType) )
    {
      return false;
    }

    if( !TypesUtil.isStructuralInterface( types(), s.tsym ) )
    {
      return false;
    }

    Map<Pair<String, String>, Boolean> cache = CACHED_PAIRS.get();
    Pair<String, String> pair = new Pair<>( t.toString(), s.toString() );
    if( cache.containsKey( pair ) )
    {
      // short-circuit null, actual result comes from initial frame
      Boolean result = cache.get( pair );
      return result == null || result;
    }
    cache.put( pair, null );
    try
    {
      // check if all non-default public instance methods in (type variable erased) structural interface `s` are provided in type `t`

      Set<MethodSymbol> sMethods = new HashSet<>();
      getAllMethods( s, m -> !m.isStatic() && (m.flags() & DEFAULT) == 0 && (m.flags() & PUBLIC) != 0 && (m.flags() & SYNTHETIC) == 0, sMethods );

      Set<MethodSymbol> tMethods = new HashSet<>();
      getAllMethods( t, m -> !m.isStatic() && (m.flags() & PUBLIC) != 0, tMethods );

      Set<VarSymbol> tFields = new HashSet<>();
      getAllFields( t, v -> !v.isStatic() && (v.flags() & PUBLIC) != 0, tFields );

      boolean result = true;
      for( MethodSymbol sm : sMethods )
      {
        if( tMethods.stream().noneMatch( tm -> isStructuralMatch( sm, tm ) ) &&
           tMethods.stream().noneMatch( tm -> isGetterRecordAccessorMatch( sm, tm ) ) &&
           tFields.stream().noneMatch( tf -> isGetterMatch( sm, tf.flatName().toString(), tf.type ) ) &&
           tFields.stream().noneMatch( tf -> isSetterFieldMatch( sm, tf ) ) )
        {
          result = false;
          break;
        }
      }
      result = result && verifyTuple( t, s, tFields );
      cache.put( pair, result );
      return result;
    }
    finally
    {
      cache.remove( pair );
    }
  }

  // Tuple type cannot have an item field that doesn't match an interface method unless all the interface methods are
  // matched by other item fields. This is to prevent accidental typos against default methods when using tuples as data
  // classes.
  default boolean verifyTuple( Type t, Type s, Set<VarSymbol> tFields )
  {
    if( !t.tsym.getSimpleName().toString().contains( "manifold_tuple_" ) )
    {
      return true;
    }

    Set<MethodSymbol> sMethods = new HashSet<>();
    // All public interface methods, including defaults
    getAllMethods( s, m -> !m.isStatic() && (m.flags() & PUBLIC) != 0 && (m.flags() & SYNTHETIC) == 0, sMethods );
    boolean allMatch = true;
    for( MethodSymbol sm: sMethods )
    {
      if( tFields.stream().noneMatch( tf -> isGetterMatch( sm, tf.flatName().toString(), tf.type ) || isSetterFieldMatch( sm, tf ) ) )
      {
        allMatch = false;
        break;
      }
    }
    if( !allMatch )
    {
      for( VarSymbol tf: tFields )
      {
        if( sMethods.stream().noneMatch( sm -> isGetterMatch( sm, tf.flatName().toString(), tf.type ) ) )
        {
          // Tuple type cannot have an item field that doesn't match an interface method unless all the interface methods are
          // matched by other item fields. This is to prevent accidental typos against default methods when using tuples as data
          // classes.
          return false;
        }
      }
    }
    return true;
  }

  default Type eraseTypeVars( Type type )
  {
//    return types().erasure( type );
    return RecursiveTypeVarEraser.eraseTypeVars( types(), type );
//    return type;
  }
  default boolean isStructuralMatch( MethodSymbol sm, MethodSymbol tm )
  {
    return sm.flatName().equals( tm.flatName() ) &&
      types().isAssignable( eraseTypeVars( tm.getReturnType() ), eraseTypeVars( sm.getReturnType() ) ) &&
      hasStructurallyEquivalentArgs( tm, sm );
  }
  default boolean isGetterMatch( MethodSymbol sm, String tName, Type tType )
  {
    Symtab symtab = Symtab.instance( JavacPlugin.instance().getContext() );
    Type returnType = sm.getReturnType();
    if( returnType == symtab.voidType || !sm.params().isEmpty() )
    {
      return false;
    }

    String smName = sm.flatName().toString();
    if( smName.length() >= 3 && smName.startsWith( "is" ) && Character.isUpperCase( smName.charAt( 2 ) ) &&
      (returnType == symtab.booleanType || returnType == types().boxedTypeOrType( symtab.booleanType )) )
    {
      smName = smName.substring( 2 ).toLowerCase();
    }
    else if( smName.length() >= 4 && smName.startsWith( "get" ) && Character.isUpperCase( smName.charAt( 3 ) ) )
    {
      smName = smName.substring( 3 ).toLowerCase();
    }
    else
    {
      return false;
    }
    return smName.equals( tName ) &&
      types().isAssignable( eraseTypeVars( tType ), eraseTypeVars( returnType ) );
  }
  default boolean isSetterFieldMatch( MethodSymbol sm, VarSymbol tf )
  {
    if( (tf.flags() & FINAL) != 0 )
    {
      return false;
    }

    Symtab symtab = Symtab.instance( JavacPlugin.instance().getContext() );
    Type returnType = sm.getReturnType();
    if( returnType != symtab.voidType || sm.params().size() != 1 )
    {
      return false;
    }

    String smName = sm.flatName().toString();
    String tfName = tf.flatName().toString();
    if( smName.length() >= 4 && smName.startsWith( "set" ) && Character.isUpperCase( smName.charAt( 3 ) ) )
    {
      smName = smName.substring( 3 ).toLowerCase();
    }
    else
    {
      return false;
    }
    return smName.equals( tfName ) &&
      types().isAssignable( eraseTypeVars( tf.type ), eraseTypeVars( sm.params().get( 0 ).type ) );
  }

  static void getAllMethods( Type t, Predicate<MethodSymbol> filter, Set<MethodSymbol> tMethods )
  {
    if( !(t instanceof Type.ClassType) )
    {
      return;
    }

    ClassSymbol tsym = (ClassSymbol)t.tsym;
    for( Symbol sym : IDynamicJdk.instance().getMembers( tsym, m -> m instanceof MethodSymbol && filter.test( (MethodSymbol)m ) ) )
    {
      tMethods.add( (MethodSymbol)sym );
    }

    getAllMethods( tsym.getSuperclass(), filter, tMethods );
    for( Type iface : tsym.getInterfaces() )
    {
      getAllMethods( iface, filter, tMethods );
    }
  }

  default boolean isGetterRecordAccessorMatch( MethodSymbol sm, MethodSymbol t )
  {
    if( !JreUtil.isJava17orLater() )
    {
      return false;
    }

    if( t.getKind() != ReflectUtil.field( ElementKind.class, "RECORD_COMPONENT" ).getStatic() )
    {
      return false;
    }

    return isGetterMatch( sm, t.flatName().toString(), t.getReturnType() );
  }

  static void getAllFields( Type t, Predicate<VarSymbol> filter, Set<VarSymbol> tFields )
  {
    if( !(t instanceof Type.ClassType) )
    {
      return;
    }

    ClassSymbol tsym = (ClassSymbol)t.tsym;
    for( Symbol sym : IDynamicJdk.instance().getMembers( tsym, m -> m instanceof VarSymbol && filter.test( (VarSymbol)m ) ) )
    {
      tFields.add( (VarSymbol)sym );
    }

    getAllFields( tsym.getSuperclass(), filter, tFields );
  }

  default boolean hasStructurallyEquivalentArgs( MethodSymbol t, MethodSymbol s )
  {
    List<VarSymbol> tParams = t.getParameters();
    List<VarSymbol> sParams = s.getParameters();
    if( tParams.size() != sParams.size() )
    {
      return false;
    }
    for( int i = 0; i < sParams.size(); i++ )
    {
      VarSymbol sParam = sParams.get( i );
      VarSymbol tParam = tParams.get( i );
      if( !types().isAssignable( eraseTypeVars( sParam.type ), eraseTypeVars( tParam.type ) ) )
      {
        return false;
      }
    }
    return true;
  }
}
