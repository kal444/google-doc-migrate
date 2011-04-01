package com.yellowaxe.gdata.gdoc;

import static java.lang.String.format;

import com.google.gdata.data.acl.AclScope;
import com.google.gdata.data.acl.AclScope.Type;

public class AclHolder {

    private AclScope.Type type;

    private String role;

    private String scope;

    public AclHolder(Type type, String role, String scope) {

        super();
        this.type = type;
        this.role = role;
        this.scope = scope;
    }

    public AclScope.Type getType() {

        return type;
    }

    public void setType(AclScope.Type type) {

        this.type = type;
    }

    public String getRole() {

        return role;
    }

    public void setRole(String role) {

        this.role = role;
    }

    public String getScope() {

        return scope;
    }

    public void setScope(String scope) {

        this.scope = scope;
    }

    @Override
    public int hashCode() {

        return getScope().hashCode();
    }

    @Override
    public boolean equals(Object obj) {

        if (obj instanceof AclHolder)
            return ((AclHolder) obj).getScope().equals(getScope());

        return false;
    }

    @Override
    public String toString() {

        return format("  acl role: %s scope: %s(%s)", getRole(), getScope(), getType());
    }
}
