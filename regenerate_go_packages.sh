#!/bin/sh

if [ "$1" == "" ]; then
    echo "Usage: $0 <dir>"
    exit 1
fi

protos=`find $1/src/main/proto -name \*.proto`

# Remove existing packages.
for i in $protos; do
    gsed -i -e '/option go_package.*/d' $i
done

# Add packages based on the pathing.
for i in $protos; do
    if [ "$(grep 'go_package' $i)" == "" ]; then
        p=$(dirname $i | gsed 's|.*/src/main/proto||g' | gsed 's|^/||g')
        if [ -z "$p" ]; then
            p="core"
        fi

        d=$(basename $i .proto)
        echo "# $i missing go package"
        echo "# p:$p"
        echo "# d:$d"
        
        gsed -i "/option java_package/ioption go_package=\"github.com/FigureTechnologies/p8e-proto-go/pkg/$p\";" $i
    fi
done
