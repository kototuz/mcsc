package server;

public class ObjectWrapper {
    public Class<?> clazz = null;
    public Object object  = null;

    public ObjectWrapper(String className) throws Exception {
        this.clazz = Class.forName(className);
    }

    public ObjectWrapper(Object object) {
        if (object != null) this.clazz = object.getClass();
        this.object = object;
    }

    public ObjectWrapper invoke(MethodSignature sig, Object... args) throws Exception {
        if (this.object == null) {
            throw new RuntimeException(
                "It looks like the prev method returns `void`"
            );
        }

        return new ObjectWrapper(
            this.clazz
                .getMethod(sig.name, sig.params)
                .invoke(this.object, args)
        );
    }

    public static ObjectWrapper invokeStatic(
        String className,
        MethodSignature sig,
        Object... args
    ) throws Exception {
        return new ObjectWrapper(
            Class.forName(className)
                .getMethod(sig.name, sig.params)
                .invoke(null, args)
        );
    }

    public static ObjectWrapper getStatic(
        String className,
        String fieldName
    ) throws Exception {
        return new ObjectWrapper(
            Class.forName(className)
                .getField(fieldName)
                .get(null)
        );
    }

    public static record MethodSignature(String name, Class<?>... params) {
        public static MethodSignature sig(String name, Class<?>... params) {
            return new MethodSignature(name, params);
        }
    }
}
