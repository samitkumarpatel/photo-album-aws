package net.samitkumar.photo_album_aws.dynamodb;

import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;

/** Creative metadata in the album partition; no additional tables or indexes. */
@DynamoDbBean
public class MetadataItem {
    private String pk, sk, json;
    @DynamoDbPartitionKey public String getPk() { return pk; }
    public void setPk(String value) { pk = value; }
    @DynamoDbSortKey public String getSk() { return sk; }
    public void setSk(String value) { sk = value; }
    public String getJson() { return json; }
    public void setJson(String value) { json = value; }
}
