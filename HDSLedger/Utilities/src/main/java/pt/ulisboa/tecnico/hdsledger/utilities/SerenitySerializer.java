package pt.ulisboa.tecnico.hdsledger.utilities;

import java.io.*;

public class SerenitySerializer {

    public static <T extends Serializable> byte[] serialize(T object) {
        try {
            ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
            ObjectOutputStream objectStream = new ObjectOutputStream(byteStream);
            objectStream.writeObject(object);
            objectStream.close();

            return byteStream.toByteArray();
        } catch (Exception e) {
            return null;
        }

    }

    public static <T extends Serializable> T deserialize(byte[] data, int offset, int length) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(data, offset, length);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteStream);


        return (T) objectInputStream.readObject(); // This is fine, it will always be the given generic
    }

    public static <T extends Serializable> T deserialize(byte[] data) throws IOException, ClassNotFoundException {
        ByteArrayInputStream byteStream = new ByteArrayInputStream(data);
        ObjectInputStream objectInputStream = new ObjectInputStream(byteStream);


        return (T) objectInputStream.readObject(); // This is fine, it will always be the given generic
    }

}
